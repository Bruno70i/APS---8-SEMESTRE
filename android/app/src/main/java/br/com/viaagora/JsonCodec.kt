package br.com.viaagora

import org.json.JSONArray
import org.json.JSONObject

data class ReportInput(
    val crossingId: String, val side: String, val category: String, val waitMinutes: Int?,
    val description: String, val latitude: Double, val longitude: Double,
) {
    fun json(): JSONObject = JSONObject().put("crossingId", crossingId).put("side", side).put("category", category)
        .put("waitMinutes", waitMinutes ?: JSONObject.NULL).put("description", description.trim())
        .put("latitude", latitude).put("longitude", longitude)
}
private fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name)
private fun JSONObject.number(name: String): Int? = if (isNull(name) || !has(name)) null else getInt(name)
private fun <T> JSONArray?.items(decode: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { runCatching { decode(getJSONObject(it)) }.getOrNull() }

fun Report.json(): JSONObject = JSONObject().put("id", id).put("authorId", authorId).put("crossingId", crossingId).put("side", side)
    .put("category", category).put("waitMinutes", waitMinutes ?: JSONObject.NULL).put("description", description)
    .put("latitude", latitude).put("longitude", longitude).put("status", status).put("version", version)
    .put("createdAt", createdAt).put("updatedAt", updatedAt)
fun decodeReport(o: JSONObject) = Report(o.getString("id"), o.getString("authorId"), o.getString("crossingId"), o.getString("side"),
    o.getString("category"), o.number("waitMinutes"), o.optString("description"), o.getDouble("latitude"), o.getDouble("longitude"),
    o.getString("status"), o.getInt("version"), o.getString("createdAt"), o.getString("updatedAt"))
fun encodeReports(data: List<Report>): String = JSONArray().apply { data.forEach { put(it.json()) } }.toString()
fun decodeReports(text: String): List<Report> = runCatching { JSONArray(text).items(::decodeReport) }.getOrDefault(emptyList())

private fun decodeTerminal(o: JSONObject) = Terminal(o.getString("name"), o.optString("address"), o.getDouble("latitude"), o.getDouble("longitude"))
private fun decodeCommunity(o: JSONObject?) = o?.let { CommunityWait(it.getInt("waitMinutes"), it.getInt("reports")) }
private fun decodeStatus(o: JSONObject?) = o?.let {
    CrossingStatus(it.getInt("waitA"), it.getInt("waitB"), it.getInt("boats"), it.getBoolean("operating"), it.text("weatherA"), it.text("weatherB"),
        it.text("period"), it.text("notice"), it.getString("source"), it.getString("collectedAt"), it.optBoolean("stale"))
}
fun decodeCrossing(o: JSONObject): Crossing {
    val community = o.optJSONObject("community")
    val id = o.getString("id")
    val points = o.optJSONArray("route")
    val route = if (points == null) crossingCatalog.firstOrNull { it.id == id }?.route.orEmpty()
        else (0 until points.length()).map { points.getJSONArray(it).let { p -> p.getDouble(0) to p.getDouble(1) } }
    return Crossing(id, o.getString("name"), o.getString("kind"), decodeTerminal(o.getJSONObject("pointA")),
        decodeTerminal(o.getJSONObject("pointB")), decodeStatus(o.optJSONObject("status")), decodeCommunity(community?.optJSONObject("A")),
        decodeCommunity(community?.optJSONObject("B")), o.optInt("activeReports"), o.optInt("stopReports"), route)
}
fun decodeCrossings(text: String): Pair<List<Crossing>, SourceInfo> {
    val o = JSONObject(text)
    val s = o.optJSONObject("source") ?: JSONObject()
    return o.optJSONArray("crossings").items(::decodeCrossing) to
        SourceInfo(s.text("mode"), s.optBoolean("available"), s.text("lastSuccessAt"), s.text("lastError"))
}
fun decodeHistory(text: String): History {
    val o = JSONObject(text)
    val best = o.optJSONObject("bestHour") ?: JSONObject()
    return History(o.getString("crossingId"),
        o.optJSONArray("hourly").items { HourAverage(it.getInt("hour"), it.getInt("waitA"), it.getInt("waitB")) }, best.number("A"), best.number("B"))
}
