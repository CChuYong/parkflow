package io.parkflow.probe

import org.junit.Assert.*
import org.junit.Test

class UserHandoffsTest {
    private var serial = 0
    private fun prompt() = ParkingPrompt { "token-${++serial}" }.apply { context("a", 10, false, true) }
    private fun ParkingPrompt.transition(now: Long = 100): ParkingPromptTicket {
        observe(false, now - 1); observe(true, now); return ticket!!
    }
    @Test fun readyConsumesUserHandoffExactlyOnce() {
        val h = NavigationHandoff(); assertFalse(h.consume(1, true))
        h.begin(100); assertFalse(h.consume(101, false)); assertTrue(h.consume(102, true)); assertFalse(h.consume(103, true))
    }
    @Test fun handoffFailureTimeoutAndLifecycleCancelNeverResumeLater() {
        val h = NavigationHandoff()
        h.begin(100); assertFalse(h.consume(101, true, true)); assertFalse(h.consume(102, true))
        h.begin(100); assertFalse(h.consume(8100, true)); assertFalse(h.consume(8200, true))
        h.begin(100); h.cancel(); assertFalse(h.consume(101, true))
    }
    @Test fun repeatedTapReplacesPendingHandoffWithoutQueueing() {
        val h = NavigationHandoff(); h.begin(100); h.begin(200)
        assertTrue(h.consume(8199, true)); assertFalse(h.consume(8199, true))
    }
    @Test fun initialPRepeatedPAndDismissedPNeverRepost() {
        val p = prompt(); p.observe(true, 1); p.observe(true, 2); assertNull(p.ticket)
        val t = p.transition(); p.observe(true, 101); assertEquals(t, p.ticket)
        p.dismiss(); p.observe(true, 102); assertNull(p.ticket)
    }
    @Test fun newTransitionRejectsOldTokenEvenOnSameSelection() {
        val p = prompt(); val old = p.transition(); val fresh = p.transition(200)
        assertNotEquals(old.token, fresh.token)
        assertFalse(p.valid(old.token, "a", 10, 201)); assertTrue(p.valid(fresh.token, "a", 10, 201))
    }
    @Test fun changedSameLotTripAndDestinationInvalidatePreviousToken() {
        val p = prompt(); val old = p.transition()
        p.context("a", 11, false, true)
        assertFalse(p.valid(old.token, "a", 10, 101)); p.observe(true, 102); assertNull(p.ticket)
        p.transition(200); p.context(null, null, false, true); assertNull(p.live(201))
    }
    @Test fun activeVisitStoppedServiceAndUnknownGearInvalidate() {
        val p = prompt(); p.transition(); p.context("a", 10, true, true); assertNull(p.ticket)
        p.context("a", 10, false, true); p.transition(200); p.context("a", 10, false, false); assertNull(p.ticket)
        p.context("a", 10, false, true); p.transition(300); p.observe(null, 301); p.observe(true, 302); assertNull(p.ticket)
        p.transition(400); p.reset(); assertNull(p.live(401))
    }
    @Test fun expiryAndDismissalNeedNewNonPTransition() {
        val p = prompt(); val t = p.transition()
        assertTrue(p.valid(t.token, "a", 10, 120099)); assertFalse(p.valid(t.token, "a", 10, 120100))
        p.observe(true, 120101); assertNull(p.ticket)
        val fresh = p.transition(120200); p.dismiss(); assertFalse(p.canSave(fresh.token, "a", 10, 120201))
    }
    @Test fun intentOpenIsOneShotButSaveStillNeedsLiveTicket() {
        val p = prompt(); val t = p.transition()
        assertFalse(p.canSave(t.token, "a", 10, 101))
        assertNull(p.open(t.token, "a", 11, 101)); assertEquals(t, p.open(t.token, "a", 10, 102))
        assertNull(p.open(t.token, "a", 10, 103)); assertTrue(p.canSave(t.token, "a", 10, 104))
        p.observe(false, 105); assertFalse(p.canSave(t.token, "a", 10, 106))
    }

    @Test fun deferOnlyConsumesMatchingUnopenedTicketAndRequiresFreshTransition() {
        val p = prompt(); val t = p.transition()
        assertFalse(p.defer(t.token, "a", 11, 101))
        assertTrue(p.defer(t.token, "a", 10, 101)); assertNull(p.live(102))
        assertFalse(p.defer(t.token, "a", 10, 103))
        p.observe(true, 104); assertNull(p.ticket)
        val next = p.transition(200); assertNotEquals(t.token, next.token)
        assertFalse(p.defer(t.token, "a", 10, 201)); assertEquals(next, p.ticket)
        assertTrue(p.defer(next.token, "a", 10, 202))
    }
    @Test fun deferCannotCloseOpenedConfirmationOrExpiredTicket() {
        val p = prompt(); val t = p.transition()
        assertEquals(t, p.open(t.token, "a", 10, 101))
        assertFalse(p.defer(t.token, "a", 10, 102)); assertTrue(p.canSave(t.token, "a", 10, 103))
        val next = p.transition(200)
        assertFalse(p.defer(next.token, "a", 10, 120200)); assertNull(p.ticket)
    }
    @Test fun deferRejectsOldTripStoppedServiceAndActiveVisit() {
        val p = prompt(); val t = p.transition(); p.context("a", 11, false, true)
        assertFalse(p.defer(t.token, "a", 10, 101))
        val next = p.transition(200); p.context("a", 11, false, false)
        assertFalse(p.defer(next.token, "a", 11, 201))
        p.context("a", 11, false, true); val active = p.transition(300); p.context("a", 11, true, true)
        assertFalse(p.defer(active.token, "a", 11, 301))
    }
}
