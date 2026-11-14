package io.parkflow.probe

import org.junit.Assert.*
import org.junit.Test

class RecommendationActionsTest {
    private val lot = ParkingLot("a", "A", "address", 37.5, 127.0, 5, 100, 5, 100, "2025-09-05")
    private val snapshot = CandidateSnapshot(1, "destination", 60, listOf(ParkingCandidate(lot, 10.0, 1200)))
    private fun gate() = RecommendationActions().apply { issue("token", snapshot, 100) }
    private fun RecommendationActions.tap(token: String = "token", generation: Long = 1,
        destination: String = "destination", minutes: Int = 60, live: CandidateSnapshot = snapshot,
        currentMinutes: Int = 60, running: Boolean = true, active: Boolean = false,
        selected: Boolean = false, dirty: Boolean = false, now: Long = 101) =
        consume(token, generation, destination, minutes, live, currentMinutes, running, active, selected, dirty, now)

    @Test fun compareAndRouteMutuallyConsumeInEitherOrderAndReplayFails() {
        // Both adapters call the same gate; whichever action arrives first owns the context.
        val compareFirst = gate(); assertTrue(compareFirst.tap()); assertFalse(compareFirst.tap())
        val routeFirst = gate(); assertTrue(routeFirst.tap()); assertFalse(routeFirst.tap())
    }
    @Test fun concurrentActionsHaveOnlyOneWinner() {
        val g = gate(); val start = java.util.concurrent.CountDownLatch(1)
        val wins = java.util.concurrent.atomic.AtomicInteger()
        val threads = List(2) { Thread { start.await(); if (g.tap()) wins.incrementAndGet() }.apply { start() } }
        start.countDown(); threads.forEach { it.join() }; assertEquals(1, wins.get())
    }
    @Test fun ttlBoundaryAndClockRollbackRejectActions() {
        assertTrue(gate().tap(now = 120099)); assertFalse(gate().tap(now = 120100))
        val g = gate(); assertFalse(g.tap(now = 99)); assertFalse(g.tap(now = 101))
    }
    @Test fun intentAndLiveSnapshotMustMatchGenerationDestinationAndDuration() {
        val g = gate()
        assertFalse(g.tap(generation = 2)); assertFalse(g.tap(destination = "other")); assertFalse(g.tap(minutes = 120))
        assertFalse(g.tap(live = snapshot.copy(generation = 2)))
        assertFalse(g.tap(live = snapshot.copy(destinationIdentity = "other")))
        assertFalse(g.tap(live = snapshot.copy(minutes = 120)))
        assertFalse(g.tap(currentMinutes = 120)); assertFalse(g.tap(live = snapshot.copy(items = emptyList())))
        assertTrue(g.tap())
    }
    @Test fun replacementAndExplicitInvalidationCannotReviveOldAction() {
        val g = gate(); g.issue("new", snapshot.copy(generation = 2), 200)
        assertFalse(g.tap(now = 201)); assertTrue(g.tap(token = "new", generation = 2, live = snapshot.copy(generation = 2), now = 201))
        val stopped = gate(); stopped.invalidate(); assertFalse(stopped.tap())
        val demo = RecommendationActions(); assertFalse(demo.tap())
        assertNull(demo.issue("demo", snapshot.copy(destinationIdentity = null), 100)); assertFalse(demo.tap())
    }
    @Test fun serviceActiveVisitSelectionAndDirtyDraftGuardsDoNotConsumeValidTicket() {
        val g = gate(); assertFalse(g.tap(running = false)); assertFalse(g.tap(active = true))
        assertFalse(g.tap(selected = true)); assertFalse(g.tap(dirty = true)); assertTrue(g.tap())
    }
}
