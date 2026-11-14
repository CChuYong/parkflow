package io.parkflow.probe

enum class ComparisonResult { NO_DESTINATION, SEARCHING, FOUND, EMPTY, UNAVAILABLE }
enum class ConnectionPhase { STOPPED, CONNECTING, READY, FAILED }
enum class FlowPage { PAIRING_INTRO, HOME, WAITING, COMPARE, REQUESTING, GUIDANCE, CONFIRM_PARKING, PARKED, EDIT_NOTES, EXIT_PREP, COMPLETED, HISTORY, DETAIL, SETTINGS, SOURCES, DIAGNOSTICS }
data class FlowRoute(val page: FlowPage, val id: String? = null, val selectionTime: Long? = null, val gear: Boolean = false)
data class FlowContext(val running: Boolean, val candidates: Boolean, val selection: NavigationSelection?, val ledger: VisitLedger, val suggestion: String?, val introSeen: Boolean = true)
/** Explicit routes survive refresh; routes that can mutate records are tied to an identity. */
object ParkingFlow {
    fun resolve(context: FlowContext, explicit: FlowRoute?): FlowRoute {
        val c = context
        if (explicit != null) when (explicit.page) {
            FlowPage.PAIRING_INTRO, FlowPage.SETTINGS, FlowPage.SOURCES, FlowPage.DIAGNOSTICS, FlowPage.HISTORY -> return explicit
            FlowPage.EDIT_NOTES, FlowPage.DETAIL -> if (c.ledger.visits.any { it.id == explicit.id }) return explicit
            FlowPage.COMPLETED -> if (c.ledger.visits.any { it.id == explicit.id && it.status == VisitStatus.CLOSED } && c.ledger.active == null) return explicit
            FlowPage.EXIT_PREP -> if (c.ledger.active?.id == explicit.id) return explicit
            FlowPage.CONFIRM_PARKING -> if (c.ledger.active == null && c.selection?.state == NavigationSelectionState.ACCEPTED && c.selection.lot.id == explicit.id && c.selection.requestedAt == explicit.selectionTime && (!explicit.gear || c.suggestion == explicit.id)) return explicit
            else -> Unit
        }
        c.ledger.active?.let { return FlowRoute(FlowPage.PARKED, it.id) }
        c.selection?.let { selection ->
            if (selection.state == NavigationSelectionState.REQUESTED) return FlowRoute(FlowPage.REQUESTING, selection.lot.id, selection.requestedAt)
            return FlowRoute(FlowPage.GUIDANCE, selection.lot.id, selection.requestedAt)
        }
        return FlowRoute(if (!c.running) { if (c.introSeen) FlowPage.HOME else FlowPage.PAIRING_INTRO } else if (c.candidates) FlowPage.COMPARE else FlowPage.WAITING)
    }
}
