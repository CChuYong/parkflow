package io.parkflow.probe

import android.content.Context
import org.json.JSONArray

fun loadParkingLots(context: Context): List<ParkingLot> {
    val rows = JSONArray(context.assets.open("gangnam-parking.json").bufferedReader().use { it.readText() })
    return (0 until rows.length()).map { i ->
        val o = rows.getJSONObject(i)
        fun number(key: String): Int? = if (o.isNull(key)) null else o.optString(key).toIntOrNull()
        ParkingLot(o.getString("id"), o.getString("name"), o.optString("address", ""),
            o.getDouble("latitude"), o.getDouble("longitude"), number("basicMinutes"), number("basicFee"),
            number("addMinutes"), number("addFee"), o.getString("sourceDate"))
    }.also { lots -> require(lots.isNotEmpty() && lots.all { it.id.isNotBlank() && it.name.isNotBlank() && validCoordinates(it.latitude, it.longitude) }) }
}
