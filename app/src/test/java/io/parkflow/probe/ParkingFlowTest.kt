package io.parkflow.probe

import org.junit.Assert.*
import org.junit.Test

class ParkingFlowTest {
    private val lot = ParkingLot("a", "주차장", "서울", 37.5, 127.0, 10, 500, 10, 500, "2025-09-05")
    private val request = NavigationSelection(lot, NavigationSelectionState.REQUESTED, "origin", 123)
    private val accepted = request.copy(state = NavigationSelectionState.ACCEPTED)
    private val active = VisitLedger().confirm(lot, EntryMode.MANUAL, 1000)
    private fun context(running: Boolean = false, candidates: Boolean = false, selection: NavigationSelection? = null, ledger: VisitLedger = VisitLedger(), suggestion: String? = null) = FlowContext(running, candidates, selection, ledger, suggestion)
    @Test fun unseenIdleShowsIntroWhileSeenIdleShowsHome() {
        assertEquals(FlowPage.PAIRING_INTRO, ParkingFlow.resolve(context().copy(introSeen = false), null).page)
        assertEquals(FlowPage.HOME, ParkingFlow.resolve(context().copy(introSeen = true), null).page)
    }
    @Test fun unseenActiveVisitRestoresBeforeIntro() {
        val route = ParkingFlow.resolve(context(ledger = active).copy(introSeen = false), null)
        assertEquals(FlowRoute(FlowPage.PARKED, active.active!!.id), route)
    }
    @Test fun unseenLiveCandidatesKeepComparisonAndWaiting() {
        assertEquals(FlowPage.COMPARE, ParkingFlow.resolve(context(true, true).copy(introSeen = false), null).page)
        assertEquals(FlowPage.WAITING, ParkingFlow.resolve(context(true).copy(introSeen = false), null).page)
    }
    @Test fun unseenLiveSelectionKeepsRequestGuidanceAndConfirmation() {
        assertEquals(FlowPage.REQUESTING, ParkingFlow.resolve(context(selection = request).copy(introSeen = false), null).page)
        assertEquals(FlowPage.GUIDANCE, ParkingFlow.resolve(context(selection = accepted).copy(introSeen = false), null).page)
        assertEquals(FlowPage.GUIDANCE, ParkingFlow.resolve(context(selection = accepted, suggestion = "a").copy(introSeen = false), null).page)
    }
    @Test fun explicitIntroCanReopenOverLiveOrSavedContext() {
        val intro = FlowRoute(FlowPage.PAIRING_INTRO)
        val live = context(true, true, accepted, active)
        assertEquals(intro, ParkingFlow.resolve(live, intro))
        assertEquals(intro, ParkingFlow.resolve(live.copy(introSeen = false), intro))
        assertEquals(FlowPage.PARKED, ParkingFlow.resolve(live, null).page)
    }
    @Test fun coldStartIgnoresClosedHistoryAndRestoresActiveWithoutSdk() {
        val closed = active.close(active.active!!.id, 2000)
        assertEquals(FlowPage.HOME, ParkingFlow.resolve(context(ledger = closed), null).page)
        assertEquals(FlowPage.PARKED, ParkingFlow.resolve(context(ledger = active), null).page)
        assertEquals(FlowPage.PARKED, ParkingFlow.resolve(context(true, true, accepted, active, "a"), null).page)
    }
    @Test fun liveDestinationFlowDistinguishesPendingAcceptedAndInvalidated() {
        assertEquals(FlowPage.WAITING, ParkingFlow.resolve(context(true), null).page)
        assertEquals(FlowPage.COMPARE, ParkingFlow.resolve(context(true, true), null).page)
        assertEquals(FlowPage.REQUESTING, ParkingFlow.resolve(context(true, selection = request), null).page)
        assertEquals(FlowPage.GUIDANCE, ParkingFlow.resolve(context(true, selection = accepted), null).page)
        assertEquals(FlowPage.WAITING, ParkingFlow.resolve(context(true), FlowRoute(FlowPage.GUIDANCE, "a")).page)
    }
    @Test fun confirmationIsBoundToAcceptedSelectionAndGearContext() {
        val route = FlowRoute(FlowPage.CONFIRM_PARKING, "a", 123, true)
        assertEquals(route, ParkingFlow.resolve(context(true, selection = accepted, suggestion = "a"), route))
        assertEquals(FlowPage.GUIDANCE, ParkingFlow.resolve(context(true, selection = accepted), route).page)
        assertEquals(FlowPage.REQUESTING, ParkingFlow.resolve(context(true, selection = request, suggestion = "a"), route).page)
        assertEquals(FlowPage.GUIDANCE, ParkingFlow.resolve(context(true, selection = accepted.copy(requestedAt = 456)), route).page)
        assertEquals(FlowPage.PARKED, ParkingFlow.resolve(context(ledger = active, selection = accepted), route).page)
    }
    @Test fun explicitRecordRoutesAndCloseAreIdentityBound() {
        val id = active.active!!.id
        val edit = FlowRoute(FlowPage.EDIT_NOTES, id)
        assertEquals(edit, ParkingFlow.resolve(context(selection = accepted, ledger = active), edit))
        val exit = FlowRoute(FlowPage.EXIT_PREP, id)
        assertEquals(exit, ParkingFlow.resolve(context(ledger = active), exit))
        val closed = active.close(id, 3000)
        assertEquals(FlowPage.HOME, ParkingFlow.resolve(context(ledger = closed), exit).page)
        val completed = FlowRoute(FlowPage.COMPLETED, id)
        assertEquals(completed, ParkingFlow.resolve(context(ledger = closed), completed))
        assertEquals(edit, ParkingFlow.resolve(context(ledger = closed), edit))
        assertEquals(FlowPage.HOME, ParkingFlow.resolve(context(), edit).page)
        listOf(FlowPage.HISTORY, FlowPage.SETTINGS, FlowPage.DIAGNOSTICS).forEach {
            val page = FlowRoute(it); assertEquals(page, ParkingFlow.resolve(context(selection = accepted, ledger = active), page))
        }
    }
    @Test fun dismissRetainsPBaselineAndNewTransitionRearms() {
        val gear = GearTransition(); gear.context("a", false); gear.observe(false); gear.observe(true)
        assertEquals("a", gear.suggestionFor); gear.dismiss(); gear.observe(true); assertNull(gear.suggestionFor)
        gear.observe(false); gear.observe(true); assertEquals("a", gear.suggestionFor)
    }
}
