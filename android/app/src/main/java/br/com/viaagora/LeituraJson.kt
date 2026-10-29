package br.com.viaagora

import org.json.JSONArray
import org.json.JSONObject

// Conversão entre o JSON da API e os modelos do app. Os nomes entre aspas são os campos do contrato com o servidor.
// Dados de um relato que ainda vai ser enviado.
data class NovoRelato(
    val travessiaId: String, val lado: String, val categoria: String, val minutosEspera: Int?,
    val descricao: String, val latitude: Double, val longitude: Double,
) {
    fun json(): JSONObject = JSONObject().put("crossingId", travessiaId).put("side", lado).put("category", categoria)
        .put("waitMinutes", minutosEspera ?: JSONObject.NULL).put("description", descricao.trim())
        .put("latitude", latitude).put("longitude", longitude)
}
private fun JSONObject.text(nome: String): String? = if (isNull(nome)) null else optString(nome)
private fun JSONObject.numero(nome: String): Int? = if (isNull(nome) || !has(nome)) null else getInt(nome)
private fun <T> JSONArray?.itens(ler: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { runCatching { ler(getJSONObject(it)) }.getOrNull() }

fun Relato.json(): JSONObject = JSONObject().put("id", id).put("authorId", autorId).put("crossingId", travessiaId).put("side", lado)
    .put("category", categoria).put("waitMinutes", minutosEspera ?: JSONObject.NULL).put("description", descricao)
    .put("latitude", latitude).put("longitude", longitude).put("status", situacao).put("version", versao)
    .put("createdAt", criadoEm).put("updatedAt", atualizadoEm)
fun lerRelato(o: JSONObject) = Relato(o.getString("id"), o.getString("authorId"), o.getString("crossingId"), o.getString("side"),
    o.getString("category"), o.numero("waitMinutes"), o.optString("description"), o.getDouble("latitude"), o.getDouble("longitude"),
    o.getString("status"), o.getInt("version"), o.getString("createdAt"), o.getString("updatedAt"))
fun codificarRelatos(dados: List<Relato>): String = JSONArray().apply { dados.forEach { put(it.json()) } }.toString()
fun lerRelatos(text: String): List<Relato> = runCatching { JSONArray(text).itens(::lerRelato) }.getOrDefault(emptyList())

private fun lerTerminal(o: JSONObject) = Terminal(o.getString("name"), o.optString("address"), o.getDouble("latitude"), o.getDouble("longitude"))
private fun lerComunidade(o: JSONObject?) = o?.let { EsperaComunidade(it.getInt("waitMinutes"), it.getInt("reports")) }
private fun lerSituacao(o: JSONObject?) = o?.let {
    SituacaoTravessia(it.getInt("waitA"), it.getInt("waitB"), it.getInt("boats"), it.getBoolean("operating"), it.text("weatherA"), it.text("weatherB"),
        it.text("period"), it.text("notice"), it.getString("source"), it.getString("collectedAt"), it.optBoolean("stale"))
}
fun lerTravessia(o: JSONObject): Travessia {
    val comunidade = o.optJSONObject("community")
    val id = o.getString("id")
    val pontos = o.optJSONArray("route")
    val rota = if (pontos == null) catalogoTravessias.firstOrNull { it.id == id }?.rota.orEmpty()
        else (0 until pontos.length()).map { pontos.getJSONArray(it).let { p -> p.getDouble(0) to p.getDouble(1) } }
    return Travessia(id, o.getString("name"), o.getString("kind"), lerTerminal(o.getJSONObject("pointA")),
        lerTerminal(o.getJSONObject("pointB")), lerSituacao(o.optJSONObject("status")), lerComunidade(comunidade?.optJSONObject("A")),
        lerComunidade(comunidade?.optJSONObject("B")), o.optInt("activeReports"), o.optInt("stopReports"), rota)
}
fun lerTravessias(text: String): Pair<List<Travessia>, InfoFonte> {
    val o = JSONObject(text)
    val s = o.optJSONObject("source") ?: JSONObject()
    return o.optJSONArray("crossings").itens(::lerTravessia) to
        InfoFonte(s.text("mode"), s.optBoolean("available"), s.text("lastSuccessAt"), s.text("lastError"))
}
fun lerHistorico(text: String): Historico {
    val o = JSONObject(text)
    val melhor = o.optJSONObject("bestHour") ?: JSONObject()
    return Historico(o.getString("crossingId"),
        o.optJSONArray("hourly").itens { MediaPorHora(it.getInt("hour"), it.getInt("waitA"), it.getInt("waitB")) }, melhor.numero("A"), melhor.numero("B"))
}
