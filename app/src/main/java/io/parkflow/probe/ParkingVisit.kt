package io.parkflow.probe

import java.util.UUID

enum class EntryMode { MANUAL, GEAR_P_CONFIRMED }
enum class VisitStatus { ACTIVE, CLOSED }
data class VisitNotes(val floor: String = "", val zone: String = "", val pillar: String = "", val note: String = "")
data class ParkingVisit(val id: String, val lot: ParkingLot, val confirmedAt: Long, val mode: EntryMode,
    val notes: VisitNotes = VisitNotes(), val status: VisitStatus = VisitStatus.ACTIVE, val endedAt: Long? = null) {
    fun elapsedMinutes(now: Long): Int = (((endedAt ?: now) - confirmedAt).coerceAtLeast(0) / 60000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
/** Mutations return new ledgers; callers persist before publishing success. */
data class VisitLedger(val visits: List<ParkingVisit> = emptyList()) {
    val active get() = visits.firstOrNull { it.status == VisitStatus.ACTIVE }
    val latest get() = active ?: visits.firstOrNull()
    fun confirm(lot: ParkingLot, mode: EntryMode, now: Long): VisitLedger {
        active?.let { require(it.lot.id == lot.id) { "기존 주차 기록을 먼저 종료하세요" }; return this }
        return copy(visits = listOf(ParkingVisit(UUID.randomUUID().toString(), lot, now, mode)) + visits.take(9))
    }
    fun notes(id: String, value: VisitNotes) = copy(visits = visits.map { if (it.id == id) it.copy(notes = value) else it })
    fun close(id: String, now: Long) = copy(visits = visits.map {
        if (it.id == id && it.status == VisitStatus.ACTIVE) it.copy(status = VisitStatus.CLOSED, endedAt = now.coerceAtLeast(it.confirmedAt)) else it
    })
}
/** Unknown/error resets the baseline; neither initial P nor recovery directly to P is an arrival. */
class GearTransition {
    private var previous: Boolean? = null
    private var context: String? = null
    var suggestionFor: String? = null; private set
    fun context(selectedId: String?, active: Boolean) {
        val next = selectedId.takeUnless { active }
        if (next != context) { context = next; suggestionFor = null }
    }
    fun observe(park: Boolean?) {
        if (park == null) { previous = null; suggestionFor = null; return }
        if (!park) suggestionFor = null
        if (previous == false && park && context != null) suggestionFor = context
        previous = park
    }
    fun dismiss() { suggestionFor = null }
    fun reset() { previous = null; suggestionFor = null }
}
