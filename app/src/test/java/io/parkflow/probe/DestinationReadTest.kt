package io.parkflow.probe

import org.junit.Assert.*
import org.junit.Test

class DestinationReadTest {
    @Test fun thrownReadCanBeRetriedAndQuietRetryNeverRepostsAnAlert() {
        val read = DestinationRead()
        val first = read.begin(100)
        read.failed(first); assertEquals(DestinationReadStatus.FAILED, read.status)
        val retry = read.begin(200, quiet = true)
        read.failed(first); assertEquals(DestinationReadStatus.READING, read.status)
        assertFalse(read.expire(first, 9000))
        assertFalse(read.received())
        assertEquals(DestinationReadStatus.IDLE, read.status)
        assertFalse(read.expire(retry, 9000))
    }
    @Test fun missingResponseIsBoundedAndLateTimeoutCannotFailSuccessfulRead() {
        val read = DestinationRead()
        val token = read.begin(100)
        assertFalse(read.expire(token, 8099)); assertTrue(read.expire(token, 8100))
        assertEquals(DestinationReadStatus.TIMED_OUT, read.status)
        val next = read.begin(8200)
        assertFalse(read.expire(token, 9000)); assertTrue(read.received())
        assertFalse(read.expire(next, 20000)); assertEquals(DestinationReadStatus.IDLE, read.status)
    }
    @Test fun coalescedNativeReadKeepsExplicitRecoveryQuietAndRebuildsFreshCandidates() {
        val lot = ParkingLot("a", "주차장", "서울", 37.5, 127.0, 10, 500, 10, 500, "2025-09-05")
        val destination = DestinationContext("목적지", "poi", 37.5, 127.0)
        val session = RecommendationSession(); session.setAutomatic(true); session.observe(destination)
        val old = CandidateSnapshot(1, destination.identity, 60, RecommendationEngine(listOf(lot)).nearby(37.5, 127.0))
        session.selected(lot, 100); assertFalse(session.canRecommend(101))
        // SDK error invalidates selection/suppression. Only explicit retry starts another read.
        session.clearSelection()
        val read = DestinationRead(); read.begin(200, quiet = true); read.begin(201)
        assertFalse(read.received()); assertTrue(session.canRecommend(202))
        val fresh = CandidateSnapshot(2, destination.identity, 60, RecommendationEngine(listOf(lot)).nearby(37.5, 127.0))
        assertEquals(lot, fresh.resolve("a", 2, destination.identity, 60))
        assertNull(fresh.resolve("a", old.generation, destination.identity, 60))
    }
}
