package io.parkflow.probe

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ProbeState {
    @Volatile var snapshot = CandidateSnapshot(0, null, 60, emptyList())
    val recommendationActions = RecommendationActions()
    @Volatile var selection: NavigationSelection? = null
    val parkingPrompt = ParkingPrompt { java.util.UUID.randomUUID().toString() }
    @Volatile var activeVisit = false
    @Volatile var suggestedLotId: String? = null; private set
    @Synchronized fun gearContext() {
        val accepted = selection?.takeIf { it.state == NavigationSelectionState.ACCEPTED }
        parkingPrompt.context(accepted?.lot?.id, accepted?.requestedAt, activeVisit, running)
        suggestedLotId = parkingPrompt.live(android.os.SystemClock.elapsedRealtime())?.lotId
    }
    @Synchronized fun gearSample(park: Boolean?) {
        gearContext(); parkingPrompt.observe(park, android.os.SystemClock.elapsedRealtime()); gearContext()
    }
    @Synchronized fun resetGear() { parkingPrompt.reset(); suggestedLotId = null }
    @Volatile var gearStatus = "기어 확인 대기 · 수동 기록 가능"
    @Volatile var result = ComparisonResult.NO_DESTINATION
    @Volatile var phase = ConnectionPhase.STOPPED
    @Volatile var destinationName: String? = null
    @Volatile var flowError: String? = null
    @Volatile var failure = FlowFailure.NONE
    @Volatile var destinationRead = DestinationReadStatus.IDLE
    @Synchronized fun dismissSuggestion() { parkingPrompt.dismiss(); suggestedLotId = null }
    @Volatile var running = false
    @Volatile var connection = "Stopped"
    @Volatile var candidates = ""
    @Volatile var recommendation = "자동 추천 대기"
    @Volatile var destination = "No destination received"
    @Synchronized fun log(context: Context, message: String) {
        Log.i("ParkFlowProbe", message)
        val prefs = context.getSharedPreferences("probe", Context.MODE_PRIVATE)
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val lines = (prefs.getString("log", "") + "\n$stamp $message").lines().takeLast(120).joinToString("\n")
        prefs.edit().putString("log", lines).apply()
    }
    fun logs(context: Context) = context.getSharedPreferences("probe", Context.MODE_PRIVATE).getString("log", "") ?: ""
}

data class DemoTarget(val latitude: Double, val longitude: Double, val name: String) {
    fun valid() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0 && name.isNotBlank()
    companion object {
        fun load(context: Context): DemoTarget {
            val prefs = context.getSharedPreferences("probe", Context.MODE_PRIVATE)
            return DemoTarget(prefs.getString("lat", "37.5665")!!.toDoubleOrNull() ?: 37.5665,
                prefs.getString("lon", "126.9780")!!.toDoubleOrNull() ?: 126.9780,
                prefs.getString("name", "Demo parking fixture")!!)
        }
    }
}
