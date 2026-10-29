package br.com.viaagora

import org.json.JSONObject
import java.time.Duration
import java.time.Instant

// Dados da rota /v1/system, mostrados na aba Bastidores.
data class NodeState(val node: String, val role: String, val alive: Boolean, val ageMs: Long, val clients: Int?, val broker: Boolean?, val collectorOk: Boolean?)
data class Delivery(val replica: String, val clients: Int, val deliveredAt: String)
data class EventTrace(
    val eventId: String, val type: String, val origin: String?, val crossingId: String?,
    val createdAt: String, val publishedAt: String?, val deliveries: List<Delivery>,
)
data class SystemState(
    val servedBy: String, val nodes: List<NodeState>, val brokerUp: Boolean, val pending: Int, val lastHour: Int,
    val replays: Int, val collections24h: Int, val events: List<EventTrace>,
)

fun decodeSystem(text: String): SystemState {
    val o = JSONObject(text)
    val nodes = o.optJSONArray("nodes")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty().map { n ->
        val info = n.optJSONObject("info") ?: JSONObject()
        NodeState(n.getString("node"), n.getString("role"), n.optBoolean("alive"), n.optLong("ageMs"),
            if (info.has("clients")) info.optInt("clients") else null, if (info.has("broker")) info.optBoolean("broker") else null,
            if (info.has("lastOk") && !info.isNull("lastOk")) info.optBoolean("lastOk") else null)
    }
    val events = o.optJSONArray("events")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty().map { e ->
        val d = e.optJSONArray("deliveries")
        EventTrace(e.getString("eventId"), e.optString("type"), e.optString("origin").takeIf { it.isNotBlank() && it != "null" },
            e.optString("crossingId").takeIf { it.isNotBlank() && it != "null" }, e.getString("createdAt"),
            e.optString("publishedAt").takeIf { it.isNotBlank() && it != "null" },
            (0 until (d?.length() ?: 0)).map { i -> d!!.getJSONObject(i).let { Delivery(it.getString("replica"), it.optInt("clients"), it.getString("deliveredAt")) } })
    }
    val outbox = o.optJSONObject("outbox") ?: JSONObject()
    return SystemState(o.optString("servedBy"), nodes, o.optJSONObject("services")?.optString("broker") == "up",
        outbox.optInt("pending"), outbox.optInt("lastHour"), o.optJSONObject("counters")?.optInt("idempotentReplays") ?: 0,
        o.optJSONObject("collections")?.optInt("last24h") ?: 0, events)
}

// Nó do desenho que representa cada réplica e o grupo de celulares ligado a ela.
fun replicaKey(name: String?) = if (name?.endsWith("2") == true) "api2" else "api1"
fun phonesKey(replicaKey: String) = if (replicaKey == "api2") "cel2" else "cel1"

data class TraceStep(val hops: List<Pair<String, String>>, val label: String, val caption: String, val concepts: List<String>, val ms: Long?)

private fun millis(from: String?, to: String?): Long? = runCatching { Duration.between(Instant.parse(from), Instant.parse(to)).toMillis().coerceAtLeast(0) }.getOrNull()

// Transforma o rastro de um evento real nos passos que a animação percorre, com o tempo medido de cada um.
fun traceSteps(event: EventTrace, now: Instant = Instant.now()): List<TraceStep> {
    val fromCollector = event.origin == "collector" || (event.origin == null && !event.type.startsWith("report"))
    val steps = mutableListOf<TraceStep>()
    if (fromCollector) {
        steps += TraceStep(listOf("semil" to "col"), "Painel oficial → coletor", "O coletor leu o painel oficial: um único acesso por minuto, para todos os usuários.", listOf("HTTP"), null)
        steps += TraceStep(listOf("col" to "db"), "Coletor → banco + outbox", "Algo mudou, então a leitura e o evento foram gravados juntos, numa transação.", listOf("Outbox"), null)
    } else {
        val api = replicaKey(event.origin); val name = event.origin ?: "API"
        steps += TraceStep(listOf(phonesKey(api) to api), "Celular → Nginx → $name", "Um celular enviou um relato por HTTPS. O Nginx, que reparte os acessos, o entregou à $name.", listOf("HTTP", "Balanceamento"), null)
        steps += TraceStep(listOf(api to "db"), "$name → banco + outbox", "A $name gravou o relato e o evento juntos, numa transação: ou salva os dois, ou nenhum.", listOf("Outbox"), null)
    }
    steps += TraceStep(listOf("db" to "wk"), "Outbox → worker", "O worker encontrou o evento pendente na tabela outbox.", listOf("Outbox"), null)
    if (event.publishedAt == null) {
        steps += TraceStep(emptyList(), "Aguardando o RabbitMQ", "O evento ainda não foi publicado: espera na outbox. Nada se perde, mesmo com o broker fora do ar.", listOf("Outbox", "Tolerância a falhas"), null)
        return steps
    }
    steps += TraceStep(listOf("wk" to "mq"), "Worker → RabbitMQ", "O worker publicou no RabbitMQ e só marcou como enviado depois da confirmação.", listOf("Pub/Sub"), millis(event.createdAt, event.publishedAt))
    val delivered = event.deliveries.sortedBy { it.replica }
    if (delivered.isEmpty()) {
        // Publicado enquanto as réplicas reconectavam ao broker: ninguém recebeu esta cópia, mas a ressincronização cobre.
        if ((millis(event.publishedAt, now.toString()) ?: 0) > 5_000) steps += TraceStep(emptyList(), "Entregue por ressincronização",
            "As réplicas ainda reconectavam ao broker quando o evento saiu. Ao voltar, elas pedem aos celulares que busquem tudo de novo: ninguém fica desatualizado.",
            listOf("Tolerância a falhas"), null)
        return steps
    }
    val fanoutMs = delivered.mapNotNull { millis(event.publishedAt, it.deliveredAt) }.maxOrNull()
    steps += TraceStep(delivered.map { "mq" to replicaKey(it.replica) }, "RabbitMQ → " + delivered.joinToString(" e ") { it.replica },
        "A exchange fanout entregou uma cópia para cada réplica: todas ficam sabendo do mesmo evento.", listOf("Pub/Sub", "Replicação"), fanoutMs)
    val phones = delivered.sumOf { it.clients }
    steps += TraceStep(delivered.map { replicaKey(it.replica) to phonesKey(replicaKey(it.replica)) }, "Réplicas → celulares",
        "Cada réplica avisou na hora, pelo WebSocket, os celulares ligados a ela ($phones conectado${if (phones == 1) "" else "s"}).", listOf("WebSocket"), null)
    return steps
}
fun totalMillis(event: EventTrace): Long? = millis(event.createdAt, event.deliveries.maxOfOrNull { it.deliveredAt } ?: event.publishedAt)

fun eventTitle(type: String) = when (type) {
    "report.created" -> "Relato publicado"
    "report.resolved" -> "Relato encerrado"
    "crossing.updated" -> "Nova leitura do painel"
    "source.status" -> "Fonte oficial mudou de estado"
    else -> "Evento"
}

// Exemplo com tempos típicos, mostrado antes do primeiro evento real.
fun exampleTrace(now: Instant = Instant.now()) = EventTrace("exemplo", "report.created", "api-2", "santos-guaruja",
    now.minusMillis(400).toString(), now.minusMillis(10).toString(),
    listOf(Delivery("api-1", 3, now.minusMillis(6).toString()), Delivery("api-2", 2, now.minusMillis(5).toString())))

data class Explanation(val title: String, val what: String, val concept: String, val ifDown: String?)
val nodeExplanations = mapOf(
    "semil" to Explanation("Site SEMIL", "O painel público das Travessias Litorâneas. Não é nosso: o sistema precisa conviver com ele lento, fora do ar ou mudando.", "HTTP", "O app continua mostrando o último dado, marcado como atrasado."),
    "col" to Explanation("Coletor", "Um único processo lê o painel a cada minuto para todos os usuários. Assim o site oficial não recebe um acesso por celular.", "Heartbeat", "Tenta de novo esperando cada vez mais, até 10 minutos."),
    "db" to Explanation("Banco (PostgreSQL)", "A fonte da verdade. Tudo é gravado aqui antes de qualquer aviso sair.", "Outbox", "As APIs recusam novos relatos até ele voltar: nada é aceito sem ser salvo."),
    "wk" to Explanation("Worker", "Lê a tabela outbox e publica os eventos pendentes no RabbitMQ, esperando a confirmação.", "Outbox", "Os eventos esperam na outbox até ele voltar."),
    "mq" to Explanation("RabbitMQ", "O broker de mensagens. Recebe cada evento uma vez e entrega uma cópia para cada réplica da API (exchange fanout).", "Pub/Sub", "Os relatos continuam sendo salvos; os avisos chegam quando ele voltar."),
    "nginx" to Explanation("Nginx (balanceador)", "A porta de entrada do servidor. Recebe todo acesso HTTPS e WebSocket dos celulares e reparte entre as duas réplicas da API.", "Balanceamento", "O app fica sem conexão e mostra o último dado salvo até ele voltar."),
    "api1" to Explanation("API réplica 1", "Uma de duas cópias idênticas da API. O Nginx divide os celulares entre elas.", "Replicação", "Os celulares dela reconectam na outra réplica, que continua atendendo."),
    "api2" to Explanation("API réplica 2", "A outra cópia da API, com o mesmo código e o mesmo banco.", "Replicação", "Os celulares dela reconectam na outra réplica, que continua atendendo."),
    "cel1" to Explanation("Celulares da réplica 1", "Aparelhos com o canal ao vivo (WebSocket) aberto na réplica 1.", "WebSocket", null),
    "cel2" to Explanation("Celulares da réplica 2", "Aparelhos com o canal ao vivo (WebSocket) aberto na réplica 2.", "WebSocket", null),
)
val conceptExplanations = linkedMapOf(
    "HTTP" to "Pedido e resposta: o celular pede ou envia dados e a resposta volta na mesma conexão.",
    "Outbox" to "O evento é gravado na mesma transação do dado. Se o broker cair, nada se perde: o worker publica depois.",
    "Pub/Sub" to "Quem publica não sabe quem vai receber. O RabbitMQ entrega uma cópia para cada interessado.",
    "Balanceamento" to "Um único endereço na frente de vários servidores: o Nginx reparte os pedidos e, se uma réplica cai, manda tudo para a outra.",
    "Replicação" to "Duas cópias iguais da API dividem os celulares. Se uma cair, a outra continua atendendo.",
    "WebSocket" to "Uma conexão que fica aberta: o servidor avisa o celular na hora, sem o celular ficar perguntando.",
    "Heartbeat" to "Cada processo avisa \"estou vivo\" a cada 5 segundos. Quem para de avisar aparece como fora do ar.",
    "Idempotência" to "Enviar o mesmo relato de novo (por exemplo, após falha de rede) não cria um relato duplicado.",
    "Tolerância a falhas" to "O sistema continua funcionando quando uma parte falha e se recupera sozinho quando ela volta.",
)
