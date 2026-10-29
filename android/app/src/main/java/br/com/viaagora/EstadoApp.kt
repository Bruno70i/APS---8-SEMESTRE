package br.com.viaagora

import java.net.URI

// Qual servidor o app usa: o oficial (VPS) ou o do computador de quem roda o projeto.
// Confere e padroniza o endereço digitado em Ajustes → Outro servidor.
fun normalizarServidor(valor: String): String {
    val uri = URI(valor.trim())
    require(uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank()) { "Use um endereço completo, começando com http:// ou https://." }
    require(uri.userInfo == null && uri.query == null && uri.fragment == null && uri.port in -1..65535) { "O endereço não deve conter senha, parâmetros ou fragmentos." }
    return URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port, uri.path.orEmpty().trimEnd('/'), null, null).toString()
}
// Servidor oficial da APS: o app abre conectado a ele, sem o usuário digitar endereço.
const val SERVIDOR_OFICIAL = "https://sedda.com.br"

// Cada troca de servidor gera uma nova "geração". Respostas que chegam de um servidor anterior são descartadas.
class EstadoApp {
    var geracao: Int = 0
        private set
    var servidor: String = ""
        private set
    fun selecionar(servidor: String) {
        this.servidor = normalizarServidor(servidor)
        geracao++
    }
    fun aceita(senha: Int): Boolean = senha == geracao
}
