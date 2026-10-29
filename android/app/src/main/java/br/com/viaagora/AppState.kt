package br.com.viaagora

import java.net.URI

fun normalizeServer(value: String): String {
    val uri = URI(value.trim())
    require(uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank()) { "Use um endereço completo, começando com http:// ou https://." }
    require(uri.userInfo == null && uri.query == null && uri.fragment == null && uri.port in -1..65535) { "O endereço não deve conter senha, parâmetros ou fragmentos." }
    return URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port, uri.path.orEmpty().trimEnd('/'), null, null).toString()
}
// Servidor oficial da APS: o app abre conectado a ele, sem o usuário digitar endereço.
const val PRODUCTION_SERVER = "https://sedda.com.br"

class AppState {
    var generation: Int = 0
        private set
    var server: String = ""
        private set
    fun select(server: String) {
        this.server = normalizeServer(server)
        generation++
    }
    fun accepts(ticket: Int): Boolean = ticket == generation
}
