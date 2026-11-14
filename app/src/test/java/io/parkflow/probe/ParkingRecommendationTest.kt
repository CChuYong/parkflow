package io.parkflow.probe

import org.junit.Assert.*
import org.junit.Test

class ParkingRecommendationTest {
    private fun lot(id: String = "a", lat: Double = 37.5, lon: Double = 127.0) = ParkingLot(id, "주차장 $id", "서울", lat, lon, 5, 400, 5, 400, "2025-09-05")
    private fun destination(name: String = "목적지", lat: Double = 37.5, lon: Double = 127.0) = DestinationContext(name, "poi-$name", lat, lon)
    @Test fun feeUsesCeilingAndValidatesOnlyNecessaryComponents() {
        assertEquals(4800L, estimateFee(lot(), 60))
        assertEquals(800L, estimateFee(lot(), 6))
        assertEquals(400L, estimateFee(lot().copy(addFee = null), 5))
        assertNull(estimateFee(lot().copy(addFee = null), 6))
        assertNull(estimateFee(lot().copy(addMinutes = 0), 60))
        assertNull(estimateFee(lot().copy(basicFee = 0), 60))
        assertNull(estimateFee(lot(), 0)); assertNull(estimateFee(lot(), -1))
    }
    @Test fun nearbyFiltersRadiusSortsTiesAndLimitsToThree() {
        val engine = RecommendationEngine(listOf(lot("c"), lot("b"), lot("a"), lot("d"), lot("far", 38.0)))
        assertEquals(listOf("a", "b", "c"), engine.nearby(37.5, 127.0).map { it.lot.id })
        val degree = Math.toDegrees(1000.0 / 6371000.0)
        val boundary = RecommendationEngine(listOf(lot("inside", 37.5 + degree * .999999), lot("outside", 37.5 + degree * 1.000001)))
        assertEquals(listOf("inside"), boundary.nearby(37.5, 127.0).map { it.lot.id })
        assertEquals(1000.0, distanceMeters(37.5, 127.0, 37.5 + degree, 127.0), .00001)
        assertTrue(engine.nearby(0.0, 0.0).isEmpty())
        assertTrue(engine.nearby(Double.NaN, 127.0).isEmpty())
        assertTrue(engine.nearby(95.0, 127.0).isEmpty())
        assertTrue(engine.nearby(35.0, 129.0).isEmpty())
    }
    @Test fun duplicateCancelNewDestinationAndInvalidCoordinatesInvalidateTokens() {
        val state = RecommendationSession(); state.setAutomatic(true)
        assertTrue(state.observe(destination()))
        state.issue("a", 0, true)
        assertFalse(state.observe(destination(lat = 37.500001)))
        assertTrue(state.consume("a", 1)); assertFalse(state.consume("a", 2))
        state.issue("b", 0, true)
        assertTrue(state.observe(destination("other"))); assertFalse(state.consume("b", 1))
        state.issue("c", 0, true); state.observe(null)
        assertFalse(state.consume("c", 1)); assertFalse(state.canRecommend())
        state.observe(destination()); state.issue("d", 0, true)
        state.observe(destination(lat = 0.0, lon = 0.0)); assertFalse(state.consume("d", 1))
    }
    @Test fun offExpiryAndReplacedTokensAreRejected() {
        val state = RecommendationSession(); state.setAutomatic(true); state.observe(destination())
        state.issue("a", 0, true); state.issue("b", 0, true)
        assertFalse(state.consume("a", 1)); assertFalse(state.consume("b", 120000))
        state.issue("c", 0, true); state.setAutomatic(false)
        assertFalse(state.canRecommend()); assertFalse(state.consume("c", 1))
        state.issue("manual", 0, false); state.observe(destination("B"))
        assertFalse(state.consume("manual", 1))
    }
    @Test fun selectedLotSuppressesReadbackEvenWithProviderNameChanges() {
        val state = RecommendationSession(); state.setAutomatic(true); state.observe(destination())
        val selected = lot(lat = 37.502)
        state.selected(selected)
        assertFalse(state.canRecommend()) // Old origin callbacks during route calculation.
        state.observe(destination("provider renamed parking", lat = 37.502001))
        assertFalse(state.canRecommend())
        state.observe(destination("unrelated", lat = 37.51)); assertTrue(state.canRecommend())
        state.selected(selected); state.observe(destination("selected", lat = selected.latitude)); state.observe(null); state.observe(destination())
        assertTrue(state.canRecommend())
    }
    @Test fun nativeReplacementCancelEmptyThenSelectedReadbackStaysSuppressed() {
        val state = RecommendationSession(); state.setAutomatic(true); state.observe(destination(), 100)
        val selected = lot(lat = 37.502)
        state.issue("tap", 100, true); assertTrue(state.consume("tap", 101))
        state.selected(selected, 101)
        // Observed Pleos sequence: onRouteCancelled, empty destination, onRouteStarted,
        // legacy onRouteEnded, requested destination info for the selected parking lot.
        state.observe(null, 102); state.invalidate()
        state.observe(destination("empty", 0.0, 0.0), 103)
        state.invalidate() // onRouteStarted also clears card/token.
        assertFalse(state.consume("tap", 104))
        state.observe(destination("provider parking name", selected.latitude), 105)
        assertFalse(state.canRecommend(105))
        // After expected acknowledgement, an ordinary cancellation clears suppression.
        state.observe(null, 106); state.observe(destination(), 107)
        assertTrue(state.canRecommend(107))
    }
    @Test fun pendingSelectionExpiresOrClearsOnUnrelatedDestinationAndFailure() {
        val state = RecommendationSession(); state.setAutomatic(true); state.observe(destination(), 0)
        val selected = lot(lat = 37.502)
        state.selected(selected, 0); state.observe(null, 1)
        state.observe(destination("late parking", selected.latitude), 30000)
        assertTrue(state.canRecommend(30000)) // TTL prevents stale selection hiding a later visit.
        state.observe(destination(), 40000); state.selected(selected, 40000)
        state.observe(destination("unrelated", 37.51), 40001)
        assertTrue(state.canRecommend(40001))
        state.observe(destination("parking again", selected.latitude), 40002)
        assertTrue(state.canRecommend(40002))
        state.selected(selected, 40003); state.clearSelection() // route exception / SDK error.
        assertTrue(state.canRecommend(40004))
    }

}
