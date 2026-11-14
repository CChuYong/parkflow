package io.parkflow.probe

import org.junit.Assert.*
import org.junit.Test

class VehicleFlowTest {
    private val lot = ParkingLot("a", "테스트 주차장", "서울", 37.5, 127.0, 5, 400, 5, 400, "2025-09-05")
    private val destination = DestinationContext("목적지", "poi", 37.51, 127.0)
    @Test fun selectedDurationRecalculatesFeesAndInvalidatesOldContext() {
        val engine = RecommendationEngine(listOf(lot))
        listOf(30 to 2400L, 60 to 4800L, 120 to 9600L, 180 to 14400L).forEach { (minutes, won) ->
            val items = engine.nearby(lot.latitude, lot.longitude, minutes)
            assertEquals(won, items.single().estimatedWon)
            assertNull(estimateFee(lot.copy(basicFee = null), minutes))
            val snapshot = CandidateSnapshot(1, destination.identity, minutes, items)
            assertEquals(lot, snapshot.resolve("a", 1, destination.identity, minutes))
            assertNull(snapshot.resolve("a", 2, destination.identity, minutes))
            assertNull(snapshot.resolve("a", 1, "new destination", minutes))
            assertNull(snapshot.resolve("a", 1, destination.identity, minutes + 1))
            assertNull(snapshot.resolve("forged lot", 1, destination.identity, minutes))
        }
    }
    @Test fun requestIsNotAcceptanceAndReadbackMustMatch() {
        val request = NavigationSelection(lot, NavigationSelectionState.REQUESTED, destination.identity, 100)
        assertEquals(NavigationSelectionState.REQUESTED, request.observe(null, 101)?.state)
        assertEquals(NavigationSelectionState.REQUESTED, request.observe(destination, 102)?.state)
        val accepted = request.observe(destination.copy(latitude = lot.latitude, name = "provider name"), 103)!!
        assertEquals(NavigationSelectionState.ACCEPTED, accepted.state)
        assertNull(accepted.observe(null, 104))
        assertNull(request.observe(destination.copy(name = "other"), 102))
        assertNull(request.observe(null, 30100))
    }
    @Test fun destinationChangeImmediatelyDropsAcceptedSelectionAndGearSuggestionButKeepsPendingHandoff() {
        val gear = GearTransition()
        val request = NavigationSelection(lot, NavigationSelectionState.REQUESTED, destination.identity, 100)
        var selection: NavigationSelection? = request.observe(destination.copy(latitude = lot.latitude), 101)
        assertEquals(NavigationSelectionState.ACCEPTED, selection!!.state)
        gear.context(selection.lot.id, false); gear.observe(false); gear.observe(true)
        assertEquals(lot.id, gear.suggestionFor)
        // Native destination-change notification arrives; no successful readback follows.
        selection = selection.destinationChanging(102)
        gear.context(selection?.lot?.id, false)
        assertNull(selection); assertNull(gear.suggestionFor)
        gear.observe(false); gear.observe(true)
        assertNull(gear.suggestionFor)
        // A user-requested route replacement still survives internal change/cancel/empty.
        val pending = request.destinationChanging(102)!!
        assertEquals(NavigationSelectionState.REQUESTED, pending.state)
        assertEquals(NavigationSelectionState.REQUESTED, pending.observe(null, 103)?.state)
        assertEquals(NavigationSelectionState.ACCEPTED, pending.observe(destination.copy(latitude = lot.latitude), 104)?.state)
        assertNull(request.destinationChanging(30100))
    }
    @Test fun repeatedSaveIsIdempotentActiveCannotBeOverwrittenAndCloseTimeIsStable() {
        val saved = VisitLedger().confirm(lot, EntryMode.MANUAL, 60000)
        assertEquals(saved, saved.confirm(lot, EntryMode.GEAR_P_CONFIRMED, 120000))
        try { saved.confirm(lot.copy(id = "other"), EntryMode.MANUAL, 120000); fail("must reject active overwrite") } catch (_: IllegalArgumentException) { }
        val id = saved.active!!.id
        val notes = VisitNotes("B2", "A", "12", "엘리베이터 옆")
        val updated = saved.notes(id, notes)
        assertEquals(60000L, updated.active!!.confirmedAt)
        assertEquals(notes, updated.active!!.notes)
        val closed = updated.close(id, 240000)
        assertNull(closed.active)
        assertEquals(3, closed.latest!!.elapsedMinutes(99999999))
        assertEquals(closed, closed.close(id, 99999999))
        val next = closed.confirm(lot.copy(id = "next"), EntryMode.MANUAL, 300000)
        assertEquals(2, next.visits.size)
    }
    @Test fun jsonRoundTripRestoresActiveNotesRateAndClosedHistory() {
        var raw: String? = null
        val store = VisitStore({ raw }, { raw = it; true })
        val first = VisitLedger().confirm(lot, EntryMode.GEAR_P_CONFIRMED, 60000)
        val saved = first.notes(first.active!!.id, VisitNotes("B1", "동", "8", "한글\n메모 \"보존\""))
        assertTrue(store.save(saved))
        assertEquals(saved, VisitStore({ raw }, { false }).load())
        val closed = saved.close(saved.active!!.id, 240000)
        assertTrue(store.save(closed))
        val restored = VisitStore({ raw }, { false }).load()
        assertEquals(closed, restored)
        assertEquals(3, restored.latest!!.elapsedMinutes(Long.MAX_VALUE))
        assertEquals(estimateFee(lot, 60), estimateFee(restored.latest!!.lot, 60))
    }
    @Test fun malformedSavedStateIsReportedAndNotOverwritten() {
        var raw = "{broken"
        val store = VisitStore({ raw }, { raw = it; true })
        assertTrue(store.load().visits.isEmpty()); assertNotNull(store.error)
        assertFalse(store.save(VisitLedger().confirm(lot, EntryMode.MANUAL, 60000)))
        assertEquals("{broken", raw)
    }
    @Test fun gearInitialPDuplicatePErrorAndActiveVisitNeverAutosuggest() {
        val gear = GearTransition(); gear.context("a", false)
        gear.observe(true); assertNull(gear.suggestionFor)
        gear.observe(true); assertNull(gear.suggestionFor)
        gear.observe(false); gear.observe(true); assertEquals("a", gear.suggestionFor)
        gear.observe(true); assertEquals("a", gear.suggestionFor)
        gear.context("b", false); assertNull(gear.suggestionFor)
        gear.observe(false); gear.observe(true); assertEquals("b", gear.suggestionFor)
        gear.context("b", true); assertNull(gear.suggestionFor)
        gear.observe(false); gear.observe(true); assertNull(gear.suggestionFor)
        gear.context("b", false); gear.observe(null); gear.observe(true); assertNull(gear.suggestionFor)
        gear.observe(false); gear.observe(true); assertEquals("b", gear.suggestionFor)
        gear.reset(); gear.observe(true); assertNull(gear.suggestionFor)
    }
}
