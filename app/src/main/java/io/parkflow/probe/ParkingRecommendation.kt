package io.parkflow.probe

import kotlin.math.*

data class ParkingLot(val id: String, val name: String, val address: String,
    val latitude: Double, val longitude: Double, val basicMinutes: Int?, val basicFee: Int?,
    val addMinutes: Int?, val addFee: Int?, val sourceDate: String) {
    // Retain the source name for provenance/persistence; make the place type explicit in UI.
    val displayName: String get() = if (name.contains("주차장")) name else "$name 주차장"
}
data class ParkingCandidate(val lot: ParkingLot, val distanceMeters: Double, val estimatedWon: Long?)
fun validCoordinates(lat: Double, lon: Double) = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)
fun distanceMeters(lat: Double, lon: Double, otherLat: Double, otherLon: Double): Double {
    val a = sin(Math.toRadians(otherLat-lat)/2).pow(2) + cos(Math.toRadians(lat))*cos(Math.toRadians(otherLat))*sin(Math.toRadians(otherLon-lon)/2).pow(2)
    return 6371000.0 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
}
fun estimateFee(lot: ParkingLot, minutes: Int): Long? {
    val baseMinutes = lot.basicMinutes?.takeIf { it > 0 } ?: return null
    val baseFee = lot.basicFee?.takeIf { it > 0 } ?: return null
    if (minutes <= 0) return null
    if (minutes <= baseMinutes) return baseFee.toLong()
    val interval = lot.addMinutes?.takeIf { it > 0 } ?: return null
    val fee = lot.addFee?.takeIf { it > 0 } ?: return null
    return baseFee.toLong() + ((minutes.toLong()-baseMinutes+interval-1)/interval)*fee
}
class RecommendationEngine(private val lots: List<ParkingLot>) {
    fun nearby(latitude: Double, longitude: Double, minutes: Int = 60): List<ParkingCandidate> {
        if (!validCoordinates(latitude, longitude)) return emptyList()
        return lots.filter { validCoordinates(it.latitude, it.longitude) }.map {
            ParkingCandidate(it, distanceMeters(latitude, longitude, it.latitude, it.longitude), estimateFee(it, minutes))
        }.filter { it.distanceMeters <= 1000.0 }.sortedWith(compareBy<ParkingCandidate> { it.distanceMeters }.thenBy { it.lot.id }).take(3)
    }
}
data class DestinationContext(val name: String, val poi: String, val latitude: Double, val longitude: Double) {
    // 0.0001 degrees is roughly 9–11 m in Seoul. Name/POI preserve distinct colocated destinations.
    val identity get() = "${name.trim()}|$poi|${(latitude*10000).roundToLong()}|${(longitude*10000).roundToLong()}"
}
/** Shared decision logic used by the service, with no Android clock or SDK dependencies. */
class RecommendationSession {
    var destination: DestinationContext? = null; private set
    var automatic = false; private set
    private var suppressedLot: ParkingLot? = null
    private var suppressedOrigin: String? = null
    private var pendingSelectionUntil: Long? = null
    private var token: String? = null
    private var tokenContext: String? = null
    private var expiresAt = 0L
    fun invalidate() { token = null; tokenContext = null }
    fun setAutomatic(value: Boolean) { automatic = value; invalidate() }
    fun clearSelection() { suppressedLot = null; suppressedOrigin = null; pendingSelectionUntil = null }
    private fun expireSelection(now: Long) {
        pendingSelectionUntil?.let { if (now >= it) clearSelection() }
    }
    fun observe(next: DestinationContext?, now: Long = 0): Boolean {
        expireSelection(now)
        val valid = next?.takeIf { validCoordinates(it.latitude, it.longitude) }
        val changed = destination?.identity != valid?.identity
        destination = valid
        if (changed || valid == null) invalidate()
        if (valid == null) {
            // Provider route replacement emits cancel/empty before starting the tapped target.
            // Preserve only this bounded pending transition; an acknowledged route cancels normally.
            if (pendingSelectionUntil == null) clearSelection()
        } else suppressedLot?.let { lot ->
            val matchesSelected = distanceMeters(valid.latitude, valid.longitude, lot.latitude, lot.longitude) <= 25
            if (matchesSelected) pendingSelectionUntil = null // Provider acknowledged selected lot.
            else if (valid.identity != suppressedOrigin) clearSelection()
        }
        return changed
    }
    fun canRecommend(now: Long = 0): Boolean {
        expireSelection(now)
        val d = destination ?: return false
        val lot = suppressedLot
        return automatic && (lot == null || (d.identity != suppressedOrigin && distanceMeters(d.latitude, d.longitude, lot.latitude, lot.longitude) > 25))
    }
    fun issue(value: String, now: Long, auto: Boolean) { token = value; tokenContext = if (auto) destination?.identity else null; expiresAt = now + 120000 }
    fun consume(value: String?, now: Long): Boolean {
        val accepted = value != null && value == token && now < expiresAt && (tokenContext == null || (automatic && tokenContext == destination?.identity))
        if (accepted || now >= expiresAt) invalidate()
        return accepted
    }
    fun selected(lot: ParkingLot, now: Long = 0) {
        suppressedLot = lot; suppressedOrigin = destination?.identity
        pendingSelectionUntil = now + 30000
        invalidate()
    }
}

val stayChoices = listOf(30, 60, 120, 180)
data class CandidateSnapshot(val generation: Long, val destinationIdentity: String?, val minutes: Int, val items: List<ParkingCandidate>) {
    fun resolve(id: String?, generation: Long, destination: String?, minutes: Int): ParkingLot? =
        if (this.generation == generation && destinationIdentity != null && destinationIdentity == destination && this.minutes == minutes)
            items.firstOrNull { it.lot.id == id }?.lot else null
}
enum class NavigationSelectionState { REQUESTED, ACCEPTED }
data class NavigationSelection(val lot: ParkingLot, val state: NavigationSelectionState, val origin: String?, val requestedAt: Long) {
    fun destinationChanging(now: Long): NavigationSelection? =
        takeIf { state == NavigationSelectionState.REQUESTED && now - requestedAt < 30000 }
    fun observe(destination: DestinationContext?, now: Long): NavigationSelection? {
        if (state == NavigationSelectionState.REQUESTED && now - requestedAt >= 30000) return null
        if (destination == null) return takeIf { state == NavigationSelectionState.REQUESTED }
        if (distanceMeters(destination.latitude, destination.longitude, lot.latitude, lot.longitude) <= 25)
            return copy(state = NavigationSelectionState.ACCEPTED)
        return takeIf { state == NavigationSelectionState.REQUESTED && destination.identity == origin }
    }
}
