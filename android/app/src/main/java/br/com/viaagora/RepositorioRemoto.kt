package br.com.viaagora

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Comunicação com o servidor: HTTP para buscar e enviar dados e WebSocket para receber os avisos ao vivo.
// As chamadas rodam fora da linha principal da tela e a última resposta fica guardada para abrir o app sem internet.
class RepositorioRemoto(private val prefs: SharedPreferences, val servidor: String) {
    private val cliente = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private val io = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    @Volatile private var fechado = false
    val autorId: String get() = prefs.getString("participant:$servidor", "") ?: ""
    fun emCache(): Panorama? = runCatching {
        val (travessias, fonte) = lerTravessias(prefs.getString("crossings:$servidor", null) ?: return null)
        Panorama(travessias, lerRelatos(prefs.getString("reports:$servidor", "[]") ?: "[]"), fonte)
    }.getOrNull()
    private fun token() = prefs.getString("token:$servidor", "") ?: ""
    private fun requisitar(method: String, caminho: String, dados: JSONObject? = null, autenticar: Boolean = false, key: String? = null): JSONObject {
        val body = dados?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val construtor = Request.Builder().url(servidor + caminho).header("User-Agent", "ViaAgora-Android/1.0")
        if (autenticar) construtor.header("Authorization", "Bearer ${token()}")
        if (key != null) construtor.header("Idempotency-Key", key)
        construtor.method(method, if (method in listOf("POST", "PATCH")) body ?: "{}".toRequestBody("application/json".toMediaType()) else null)
        return cliente.newCall(construtor.build()).execute().use { resposta ->
            val text = resposta.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (!resposta.isSuccessful) {
                if (resposta.code == 401) prefs.edit().remove("token:$servidor").remove("participant:$servidor").apply()
                error(json.optString("message", "Servidor respondeu com erro ${resposta.code}."))
            }
            json
        }
    }
    private fun garantirParticipante() {
        if (token().isNotEmpty()) return
        val resultado = requisitar("POST", "/v1/participants", JSONObject())
        prefs.edit().putString("participant:$servidor", resultado.getString("participantId")).putString("token:$servidor", resultado.getString("token")).commit()
    }
    private fun executarEmSegundoPlano(aoErro: (String) -> Unit, acao: () -> Unit) {
        if (fechado) return
        runCatching { io.execute { try { acao() } catch (e: Exception) {
            val message = e.message?.takeIf { it.length < 180 } ?: "Não foi possível alcançar o servidor. Confira a conexão."
            principal.post { if (!fechado) aoErro(message) }
        } } }
    }
    // Busca as travessias e os relatos e guarda uma cópia local.
    fun buscar(aoSucesso: (Panorama) -> Unit, aoErro: (String) -> Unit) = executarEmSegundoPlano(aoErro) {
        val textoTravessias = requisitar("GET", "/v1/crossings").toString()
        val relatos = lerRelatos(requisitar("GET", "/v1/reports?limit=500").getJSONArray("reports").toString())
        val (travessias, fonte) = lerTravessias(textoTravessias)
        prefs.edit().putString("crossings:$servidor", textoTravessias).putString("reports:$servidor", codificarRelatos(relatos)).apply()
        principal.post { if (!fechado) aoSucesso(Panorama(travessias, relatos, fonte)) }
    }
    fun historico(travessiaId: String, aoSucesso: (Historico) -> Unit, aoErro: (String) -> Unit) = executarEmSegundoPlano(aoErro) {
        val historico = lerHistorico(requisitar("GET", "/v1/crossings/$travessiaId/history?days=7").toString())
        principal.post { if (!fechado) aoSucesso(historico) }
    }
    fun sistema(aoSucesso: (EstadoSistema) -> Unit, aoErro: (String) -> Unit) = executarEmSegundoPlano(aoErro) {
        val estado = lerSistema(requisitar("GET", "/v1/system").toString())
        principal.post { if (!fechado) aoSucesso(estado) }
    }
    // Envia um relato. A chave de idempotência fica salva até o servidor confirmar: se a rede cair e o app
    // tentar de novo, o servidor reconhece o mesmo envio e não cria um relato duplicado.
    fun criar(entrada: NovoRelato, aoSucesso: (Relato) -> Unit, aoErro: (String) -> Unit) = executarEmSegundoPlano(aoErro) {
        garantirParticipante()
        val body = entrada.json()
        val impressaoDigital = MessageDigest.getInstance("SHA-256").digest(body.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        val chavePendente = "pending:$servidor:$impressaoDigital"
        val key = prefs.getString(chavePendente, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(chavePendente, it).commit() }
        val item = lerRelato(requisitar("POST", "/v1/reports", body, true, key).getJSONObject("report"))
        prefs.edit().remove(chavePendente).apply()
        principal.post { if (!fechado) aoSucesso(item) }
    }
    fun encerrar(id: String, aoSucesso: (Relato) -> Unit, aoErro: (String) -> Unit) = executarEmSegundoPlano(aoErro) {
        garantirParticipante()
        val item = lerRelato(requisitar("PATCH", "/v1/reports/$id/resolve", JSONObject(), true).getJSONObject("report"))
        principal.post { if (!fechado) aoSucesso(item) }
    }
    // aoEvento recebe cada mensagem do WebSocket (tipo, réplica que entregou, se é um evento rastreável) para a aba Bastidores.
    fun conectar(aoMudar: () -> Unit, aoMudarConexao: (Boolean) -> Unit, aoEvento: (String, String?, Boolean) -> Unit = { _, _, _ -> }) {
        if (fechado) return
        val endereco = servidor.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/v1/events"
        socket = cliente.newWebSocket(Request.Builder().url(endereco).build(), object: WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val evento = runCatching { JSONObject(text) }.getOrNull() ?: return
                principal.post { if (!fechado) {
                    aoEvento(evento.optString("type"), evento.optString(if (evento.has("replica")) "replica" else "via").takeIf { it.isNotBlank() }, evento.has("eventId"))
                    when (evento.optString("type")) {
                        "ready", "resync" -> { aoMudarConexao(evento.optBoolean("connected", true)); aoMudar() }
                        "broker.status" -> aoMudarConexao(evento.optBoolean("connected", false))
                        else -> aoMudar()
                    }
                } }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, resposta: Response?) { reconectar() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { reconectar() }
            private fun reconectar() { principal.post { if (!fechado) { aoMudarConexao(false); principal.postDelayed({ conectar(aoMudar, aoMudarConexao, aoEvento) }, 2500) } } }
        })
    }
    fun fechar() { fechado = true; principal.removeCallbacksAndMessages(null); socket?.cancel(); cliente.dispatcher.cancelAll(); io.shutdownNow() }
}
