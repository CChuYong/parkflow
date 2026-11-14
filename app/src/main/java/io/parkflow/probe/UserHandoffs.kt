package io.parkflow.probe

/** Activity-owned permission to leave for navigation; never persisted across lifecycle exits. */
class NavigationHandoff {
    private var tappedAt: Long? = null
    fun begin(now: Long) { tappedAt = now }
    val pending get() = tappedAt != null
    fun cancel() { tappedAt = null }
    fun expire(now: Long): Boolean {
        val start = tappedAt ?: return false
        if (now >= start && now - start < 8_000) return false
        cancel(); return true
    }
    fun consume(now: Long, ready: Boolean, failed: Boolean = false): Boolean {
        val start = tappedAt ?: return false
        if (failed || now < start || now - start >= 8_000) { cancel(); return false }
        if (!ready) return false
        cancel(); return true
    }
}

data class ParkingPromptTicket(val token: String, val lotId: String, val requestedAt: Long, val issuedAt: Long)

/** Live process-only transition identity. Opening is one-shot; saving still needs live validity. */
class ParkingPrompt(private val newToken: () -> String) {
    private var selection: Pair<String, Long>? = null
    private var previousPark: Boolean? = null
    private var opened = false
    var ticket: ParkingPromptTicket? = null; private set
    fun context(lotId: String?, requestedAt: Long?, activeVisit: Boolean, running: Boolean) {
        val next = if (lotId != null && requestedAt != null && !activeVisit && running) lotId to requestedAt else null
        if (next != selection) { selection = next; ticket = null; opened = false; previousPark = null }
    }
    fun observe(park: Boolean?, now: Long) {
        if (park == true && previousPark == false && selection != null) {
            val key = selection!!
            ticket = ParkingPromptTicket(newToken(), key.first, key.second, now); opened = false
        } else if (park != true) { ticket = null; opened = false }
        previousPark = park
    }
    fun live(now: Long): ParkingPromptTicket? {
        ticket?.let { if (now < it.issuedAt || now - it.issuedAt >= 120_000) dismiss() }
        return ticket
    }
    fun valid(token: String?, lotId: String?, requestedAt: Long?, now: Long): Boolean =
        live(now)?.let { it.token == token && it.lotId == lotId && it.requestedAt == requestedAt } == true
    fun open(token: String?, lotId: String?, requestedAt: Long?, now: Long): ParkingPromptTicket? {
        if (opened || !valid(token, lotId, requestedAt, now)) return null
        opened = true; return ticket
    }
    /** Explicit defer only owns this still-unopened transition; removal is not defer. */
    fun defer(token: String?, lotId: String?, requestedAt: Long?, now: Long): Boolean {
        if (opened || !valid(token, lotId, requestedAt, now)) return false
        dismiss(); return true
    }
    fun canSave(token: String?, lotId: String?, requestedAt: Long?, now: Long) = opened && valid(token, lotId, requestedAt, now)
    fun dismiss() { ticket = null; opened = false }
    fun reset() { selection = null; previousPark = null; dismiss() }
}


/** Shared process-only permission for either real recommendation action, never both. */
data class RecommendationTicket(val token: String, val generation: Long, val destination: String,
    val minutes: Int, val issuedAt: Long)
class RecommendationActions {
    private var ticket: RecommendationTicket? = null
    @Synchronized fun issue(token: String, snapshot: CandidateSnapshot, now: Long): RecommendationTicket? {
        ticket = snapshot.destinationIdentity?.takeIf { snapshot.items.isNotEmpty() }?.let {
            RecommendationTicket(token, snapshot.generation, it, snapshot.minutes, now)
        }
        return ticket
    }
    @Synchronized fun invalidate() { ticket = null }
    @Synchronized fun consume(token: String?, generation: Long, destination: String?, minutes: Int,
        snapshot: CandidateSnapshot, currentMinutes: Int, running: Boolean, activeVisit: Boolean,
        selected: Boolean, dirtyDraft: Boolean, now: Long): Boolean {
        val t = ticket ?: return false
        if (now < t.issuedAt || now - t.issuedAt >= 120_000) { ticket = null; return false }
        if (token != t.token || generation != t.generation || destination != t.destination || minutes != t.minutes ||
            snapshot.generation != t.generation || snapshot.destinationIdentity != t.destination ||
            snapshot.minutes != t.minutes || currentMinutes != t.minutes || snapshot.items.isEmpty() ||
            !running || activeVisit || selected || dirtyDraft) return false
        ticket = null
        return true
    }
}
