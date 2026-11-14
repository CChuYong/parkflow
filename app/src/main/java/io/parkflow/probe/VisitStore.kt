package io.parkflow.probe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class VisitStore(private val read: () -> String?, private val write: (String) -> Boolean, private val logError: (String) -> Unit = {}) {
    constructor(context: Context) : this(
        { context.getSharedPreferences("parking_visits", Context.MODE_PRIVATE).getString("visits", "[]") },
        { json -> context.getSharedPreferences("parking_visits", Context.MODE_PRIVATE).edit().putString("visits", json).commit() },
        { message -> ProbeState.log(context, message) })
    var error: String? = null; private set
    fun load(): VisitLedger = try {
        error = null
        val rows = JSONArray(read() ?: "[]")
        val visits = (0 until rows.length()).map { i ->
            val o = rows.getJSONObject(i); val l = o.getJSONObject("lot"); val n = o.getJSONObject("notes")
            fun number(key: String) = if (l.isNull(key)) null else l.getInt(key)
            val lot = ParkingLot(l.getString("id"), l.getString("name"), l.getString("address"), l.getDouble("latitude"), l.getDouble("longitude"),
                number("basicMinutes"), number("basicFee"), number("addMinutes"), number("addFee"), l.getString("sourceDate"))
            require(lot.id.isNotBlank() && lot.name.isNotBlank() && validCoordinates(lot.latitude, lot.longitude))
            val visit = ParkingVisit(o.getString("id"), lot, o.getLong("confirmedAt"), EntryMode.valueOf(o.getString("mode")),
                VisitNotes(n.getString("floor"), n.getString("zone"), n.getString("pillar"), n.getString("note")),
                VisitStatus.valueOf(o.getString("status")), if (o.isNull("endedAt")) null else o.getLong("endedAt"))
            require(visit.id.isNotBlank() && visit.confirmedAt > 0 && ((visit.status == VisitStatus.ACTIVE && visit.endedAt == null) ||
                (visit.status == VisitStatus.CLOSED && visit.endedAt != null && visit.endedAt >= visit.confirmedAt)))
            visit
        }
        require(visits.size <= 10 && visits.count { it.status == VisitStatus.ACTIVE } <= 1 && visits.map { it.id }.distinct().size == visits.size)
        VisitLedger(visits)
    } catch (e: Exception) { error = "저장된 주차 기록을 읽을 수 없습니다. 원본은 보존됩니다. 진단 로그를 확인하세요."; logError("Visit decode failed: $e"); VisitLedger() }
    fun save(ledger: VisitLedger): Boolean {
        // A malformed original must never be silently replaced by an empty recovery ledger.
        if (error != null) return false
        val rows = JSONArray()
        ledger.visits.forEach { v ->
            val l = v.lot
            val lot = JSONObject().put("id", l.id).put("name", l.name).put("address", l.address).put("latitude", l.latitude).put("longitude", l.longitude)
                .put("basicMinutes", l.basicMinutes ?: JSONObject.NULL).put("basicFee", l.basicFee ?: JSONObject.NULL)
                .put("addMinutes", l.addMinutes ?: JSONObject.NULL).put("addFee", l.addFee ?: JSONObject.NULL).put("sourceDate", l.sourceDate)
            val notes = JSONObject().put("floor", v.notes.floor).put("zone", v.notes.zone).put("pillar", v.notes.pillar).put("note", v.notes.note)
            rows.put(JSONObject().put("id", v.id).put("lot", lot).put("confirmedAt", v.confirmedAt).put("mode", v.mode.name)
                .put("notes", notes).put("status", v.status.name).put("endedAt", v.endedAt ?: JSONObject.NULL))
        }
        return write(rows.toString())
    }
}
