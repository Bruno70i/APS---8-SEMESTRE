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

class RemoteRepository(private val prefs: SharedPreferences, val base: String) {
    private val client = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    @Volatile private var closed = false
    val authorId: String get() = prefs.getString("participant:$base", "") ?: ""
    fun cached(): Snapshot? = runCatching {
        val (crossings, source) = decodeCrossings(prefs.getString("crossings:$base", null) ?: return null)
        Snapshot(crossings, decodeReports(prefs.getString("reports:$base", "[]") ?: "[]"), source)
    }.getOrNull()
    private fun token() = prefs.getString("token:$base", "") ?: ""
    private fun request(method: String, path: String, data: JSONObject? = null, auth: Boolean = false, key: String? = null): JSONObject {
        val body = data?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder().url(base + path).header("User-Agent", "ViaAgora-Android/1.0")
        if (auth) builder.header("Authorization", "Bearer ${token()}")
        if (key != null) builder.header("Idempotency-Key", key)
        builder.method(method, if (method in listOf("POST", "PATCH")) body ?: "{}".toRequestBody("application/json".toMediaType()) else null)
        return client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (!response.isSuccessful) {
                if (response.code == 401) prefs.edit().remove("token:$base").remove("participant:$base").apply()
                error(json.optString("message", "Servidor respondeu com erro ${response.code}."))
            }
            json
        }
    }
    private fun ensureParticipant() {
        if (token().isNotEmpty()) return
        val result = request("POST", "/v1/participants", JSONObject())
        prefs.edit().putString("participant:$base", result.getString("participantId")).putString("token:$base", result.getString("token")).commit()
    }
    private fun submit(onError: (String) -> Unit, action: () -> Unit) {
        if (closed) return
        runCatching { io.execute { try { action() } catch (e: Exception) {
            val message = e.message?.takeIf { it.length < 180 } ?: "Não foi possível alcançar o servidor. Confira a conexão."
            main.post { if (!closed) onError(message) }
        } } }
    }
    fun fetch(onSuccess: (Snapshot) -> Unit, onError: (String) -> Unit) = submit(onError) {
        val crossingsText = request("GET", "/v1/crossings").toString()
        val reports = decodeReports(request("GET", "/v1/reports?limit=500").getJSONArray("reports").toString())
        val (crossings, source) = decodeCrossings(crossingsText)
        prefs.edit().putString("crossings:$base", crossingsText).putString("reports:$base", encodeReports(reports)).apply()
        main.post { if (!closed) onSuccess(Snapshot(crossings, reports, source)) }
    }
    fun history(crossingId: String, onSuccess: (History) -> Unit, onError: (String) -> Unit) = submit(onError) {
        val history = decodeHistory(request("GET", "/v1/crossings/$crossingId/history?days=7").toString())
        main.post { if (!closed) onSuccess(history) }
    }
    fun system(onSuccess: (SystemState) -> Unit, onError: (String) -> Unit) = submit(onError) {
        val state = decodeSystem(request("GET", "/v1/system").toString())
        main.post { if (!closed) onSuccess(state) }
    }
    fun create(input: ReportInput, onSuccess: (Report) -> Unit, onError: (String) -> Unit) = submit(onError) {
        ensureParticipant()
        val body = input.json()
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(body.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        val prefKey = "pending:$base:$fingerprint"
        val key = prefs.getString(prefKey, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(prefKey, it).commit() }
        val item = decodeReport(request("POST", "/v1/reports", body, true, key).getJSONObject("report"))
        prefs.edit().remove(prefKey).apply()
        main.post { if (!closed) onSuccess(item) }
    }
    fun resolve(id: String, onSuccess: (Report) -> Unit, onError: (String) -> Unit) = submit(onError) {
        ensureParticipant()
        val item = decodeReport(request("PATCH", "/v1/reports/$id/resolve", JSONObject(), true).getJSONObject("report"))
        main.post { if (!closed) onSuccess(item) }
    }
    // onEvent recebe cada mensagem do WebSocket (tipo, réplica que entregou, se é um evento rastreável) para a aba Bastidores.
    fun connect(onChange: () -> Unit, onConnection: (Boolean) -> Unit, onEvent: (String, String?, Boolean) -> Unit = { _, _, _ -> }) {
        if (closed) return
        val address = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/v1/events"
        socket = client.newWebSocket(Request.Builder().url(address).build(), object: WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { JSONObject(text) }.getOrNull() ?: return
                main.post { if (!closed) {
                    onEvent(event.optString("type"), event.optString(if (event.has("replica")) "replica" else "via").takeIf { it.isNotBlank() }, event.has("eventId"))
                    when (event.optString("type")) {
                        "ready", "resync" -> { onConnection(event.optBoolean("connected", true)); onChange() }
                        "broker.status" -> onConnection(event.optBoolean("connected", false))
                        else -> onChange()
                    }
                } }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { reconnect() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { reconnect() }
            private fun reconnect() { main.post { if (!closed) { onConnection(false); main.postDelayed({ connect(onChange, onConnection, onEvent) }, 2500) } } }
        })
    }
    fun close() { closed = true; main.removeCallbacksAndMessages(null); socket?.cancel(); client.dispatcher.cancelAll(); io.shutdownNow() }
}
