package br.com.viaagora

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.*
import java.time.Duration
import java.time.Instant

// Aba Bastidores: mostra o sistema distribuído funcionando, com os dados reais de /v1/system.
class BastidoresPage(private val context: Context, private val ui: Ui) {
    // Quem fornece o estado do servidor (a rota /v1/system).
    var source: (((SystemState) -> Unit, (String) -> Unit) -> Unit)? = null
    var myReplica: String? = null
    // Endereço do servidor conectado: a VPS oficial ou o computador de quem roda o sistema localmente.
    var server: String = PRODUCTION_SERVER
        set(value) { field = value; network.serverLabel = if (value == PRODUCTION_SERVER) "VPS · SEDDA.COM.BR" else "SERVIDOR · ${value.substringAfter("://").uppercase()}" }
    private val handler = Handler(Looper.getMainLooper())
    private val content = ui.column().apply { setPadding(context.dp(16), context.dp(4), context.dp(16), context.dp(96)) }
    val view = ScrollView(context).apply { addView(content) }
    private val intro = ui.text("", 14f, Palette.muted).apply { setLineSpacing(0f, 1.15f) }
    private val network = NetworkView(context, ::explainNode)
    private val stepTag = ui.text("", 11f, Palette.accent, true).apply { letterSpacing = 0.08f }
    private val caption = ui.text("", 15f, Palette.ink).apply { setLineSpacing(0f, 1.2f); minLines = 3 }
    private val chips = linkedMapOf<String, TextView>()
    private val actions = ui.row()
    private val timelineTitle = ui.text("", 13f, Palette.muted)
    private val timeline = ui.column()
    private val metrics = ui.row()
    private var state: SystemState? = null
    private var latest: EventTrace? = null
    private var lastPlayed: String? = null
    private var playing = false
    private var lesson: List<TraceStep>? = null
    private var lessonIndex = 0
    private var visible = false
    private val poll = object : Runnable { override fun run() { refresh(); handler.postDelayed(this, 5_000) } }

    init {
        content.addView(ui.text("SISTEMA DISTRIBUÍDO", 11f, Palette.accent, true).apply { letterSpacing = 0.1f; setPadding(0, context.dp(8), 0, context.dp(6)) })
        content.addView(ui.text("Por dentro do ViaAgora", 24f, Palette.ink, true))
        content.addView(ui.space(6))
        content.addView(intro)
        content.addView(ui.space(16))
        val diagram = ui.column().apply { setPadding(context.dp(10), context.dp(12), context.dp(10), context.dp(14)); background = ui.rounded(Color.WHITE, 22) }
        diagram.addView(network)
        diagram.addView(ui.text(legend(), 12f, Palette.muted).apply { gravity = Gravity.CENTER; setPadding(0, context.dp(10), 0, 0) })
        content.addView(diagram)
        content.addView(ui.space(12))
        val explain = ui.column().apply { setPadding(context.dp(18), context.dp(14), context.dp(18), context.dp(14)); background = ui.rounded(Palette.soft, 18) }
        explain.addView(stepTag); explain.addView(ui.space(6)); explain.addView(caption)
        content.addView(explain)
        content.addView(ui.space(12))
        val chipRow = ui.row()
        conceptExplanations.keys.forEach { concept ->
            val chip = ui.chip(concept, false) { explainConcept(concept) }
            chips[concept] = chip
            chipRow.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, context.dp(36)).apply { marginEnd = context.dp(6) })
        }
        content.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) })
        content.addView(ui.space(14))
        content.addView(actions)
        content.addView(section("O caminho de um evento"))
        content.addView(timelineTitle)
        content.addView(ui.space(8))
        content.addView(timeline)
        content.addView(section("Agora no servidor"))
        content.addView(metrics)
        idleCaption()
        renderActions()
    }

    fun show() { if (visible) return; visible = true; handler.post(poll) }
    fun hide() { visible = false; handler.removeCallbacks(poll) }
    fun reset() { state = null; latest = null; lastPlayed = null; myReplica = null; lesson = null; network.stop(); playing = false; render(); if (visible) handler.postDelayed({ refresh() }, 300) }

    // Evento ao vivo do WebSocket: a réplica se apresenta no "ready"; nos demais, buscamos o rastro completo.
    fun onLiveEvent(type: String, replica: String?, hasEventId: Boolean) {
        if (type == "ready" && replica != null) { myReplica = replica; render() }
        if (hasEventId && visible) handler.postDelayed({ refresh() }, 1200)
    }

    private fun refresh() {
        val load = source
        if (load == null) return
        load({ s -> state = s; s.events.firstOrNull()?.let { latest = it }; render(); autoplay() }, { if (visible) { state = null; render() } })
    }
    private fun autoplay() {
        val event = latest ?: return
        // A chave muda quando o mesmo evento avança (ex.: estava preso na outbox e o broker voltou), e então reanimamos.
        val key = "${event.eventId}:${event.publishedAt != null}:${event.deliveries.size}"
        if (!visible || lesson != null || playing || key == lastPlayed) return
        val resumed = lastPlayed?.startsWith("${event.eventId}:false") == true
        lastPlayed = key
        val steps = traceSteps(event)
        if (resumed) {
            val start = steps.indexOfFirst { it.hops == listOf("wk" to "mq") }.coerceAtLeast(0)
            stepTag.text = "RECUPERAÇÃO"; caption.text = "O broker voltou: o worker publica o evento que estava guardado na outbox."
            handler.postDelayed({ playAll(steps, colorFor(event), start) }, 1500)
        } else playAll(steps, colorFor(event))
    }
    private fun colorFor(event: EventTrace) = Color.parseColor(if (event.type.startsWith("report")) "#D85A30" else "#378ADD")
    private fun playAll(steps: List<TraceStep>, color: Int, index: Int = 0) {
        if (index >= steps.size || !visible) { playing = false; return }
        playing = true
        val step = steps[index]
        showStep(step, "Passo ${index + 1} de ${steps.size}")
        network.play(step.hops, color) { handler.postDelayed({ playAll(steps, color, index + 1) }, 450) }
    }
    private fun showStep(step: TraceStep, position: String) {
        stepTag.text = position.uppercase(); caption.text = step.caption
        chips.forEach { (concept, chip) ->
            val on = concept in step.concepts
            chip.background = ui.rounded(if (on) Palette.accent else Palette.soft, 20); chip.setTextColor(if (on) Color.WHITE else Palette.ink)
        }
    }

    private fun render() {
        val s = state
        intro.text = when {
            s == null -> "Conectando ao servidor…"
            else -> "Estes processos trabalham juntos no servidor. Seu celular está ligado à ${myReplica?.let { "réplica $it" } ?: "uma das réplicas"}, destacada no desenho."
        }
        network.health = health(s)
        network.you = myReplica?.let { replicaKey(it) }
        network.phoneLabels = listOf("api1", "api2").associate { key ->
            val clients = s?.nodes?.firstOrNull { it.role == "api" && replicaKey(it.node) == key }?.clients
            val mine = myReplica != null && replicaKey(myReplica) == key
            phonesKey(key) to when {
                clients == null -> if (mine) "Você" else "Celulares"
                mine -> if (clients > 1) "Você +${clients - 1}" else "Você"
                else -> "$clients celular${if (clients == 1) "" else "es"}"
            }
        }
        renderTimeline()
        if (lesson == null) renderActions()
        metrics.removeAllViews()
        listOf(Triple("bolt", "eventos na última hora", s?.lastHour), Triple("queue", "esperando na outbox", s?.pending), Triple("repeat", "reenvios ignorados", s?.replays))
            .forEachIndexed { i, (name, label, value) ->
                val box = ui.column().apply { setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12)); background = ui.rounded(Color.WHITE, 16); isClickable = true }
                box.addView(icon(name, 22, Palette.accent))
                box.addView(ui.text(value?.toString() ?: "—", 22f, Palette.ink, true).apply { setPadding(0, context.dp(10), 0, 0) })
                box.addView(ui.text(label, 11f, Palette.muted).apply { setPadding(0, context.dp(4), 0, 0) })
                box.setOnClickListener { explainConcept(listOf("Pub/Sub", "Outbox", "Idempotência")[i]) }
                metrics.addView(box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = context.dp(8) })
            }
    }
    private fun health(s: SystemState?): Map<String, NetworkView.Health> {
        if (s == null) return emptyMap()
        fun of(alive: Boolean?) = when (alive) { true -> NetworkView.Health.UP; false -> NetworkView.Health.DOWN; null -> NetworkView.Health.UNKNOWN }
        val apis = s.nodes.filter { it.role == "api" }
        val collector = s.nodes.firstOrNull { it.role == "collector" }
        return mapOf(
            "api1" to of(apis.firstOrNull { replicaKey(it.node) == "api1" }?.alive), "api2" to of(apis.firstOrNull { replicaKey(it.node) == "api2" }?.alive),
            "wk" to of(s.nodes.firstOrNull { it.role == "worker" }?.alive), "col" to of(collector?.alive),
            "db" to NetworkView.Health.UP, "nginx" to NetworkView.Health.UP, "mq" to of(s.brokerUp), "semil" to of(collector?.collectorOk),
            "cel1" to of(apis.firstOrNull { replicaKey(it.node) == "api1" }?.clients?.let { it > 0 }?.takeIf { it }),
            "cel2" to of(apis.firstOrNull { replicaKey(it.node) == "api2" }?.clients?.let { it > 0 }?.takeIf { it }),
        )
    }
    private fun renderTimeline() {
        timeline.removeAllViews()
        val event = latest
        if (event == null) { timelineTitle.text = "Nenhum evento ainda. Publique um relato e volte aqui."; return }
        timelineTitle.text = if (event.eventId == "exemplo") "Exemplo · relato publicado" else "${eventTitle(event.type)} · ${ago(event.createdAt)}"
        val box = ui.column().apply { setPadding(context.dp(16), context.dp(10), context.dp(16), context.dp(10)); background = ui.rounded(Color.WHITE, 18) }
        traceSteps(event).forEach { step ->
            val row = ui.row().apply { setPadding(0, context.dp(7), 0, context.dp(7)) }
            val name = step.hops.firstOrNull()?.second?.let { NetworkView.iconOf(it) } ?: if (step.label.startsWith("Entregue")) "repeat" else "clock"
            row.addView(icon(name, 18, Palette.accent).apply { background = ui.rounded(Palette.soft, 10); setPadding(context.dp(7), context.dp(7), context.dp(7), context.dp(7)) },
                LinearLayout.LayoutParams(context.dp(32), context.dp(32)).apply { marginEnd = context.dp(12) })
            row.addView(ui.text(step.label, 14f, Palette.ink), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ui.text(step.ms?.let { "+$it ms" } ?: "", 13f, Palette.muted))
            box.addView(row)
        }
        totalMillis(event)?.takeIf { event.deliveries.isNotEmpty() }?.let { total ->
            box.addView(View(context).apply { setBackgroundColor(Palette.line) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = context.dp(6) })
            val row = ui.row().apply { setPadding(0, context.dp(12), 0, context.dp(4)) }
            row.addView(ui.text("Do banco até os celulares", 14f, Palette.ink, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ui.text("$total ms", 15f, Palette.accent, true))
            box.addView(row)
        }
        timeline.addView(box)
    }
    private fun renderActions() {
        actions.removeAllViews()
        val steps = lesson
        val buttons = if (steps == null) listOf(
            "▶  Assistir" to { replay() },
            "Passo a passo" to { startLesson() },
        ) else listOf(
            "Voltar" to { lessonStep(lessonIndex - 1) },
            (if (lessonIndex >= steps.size - 1) "Concluir" else "Próximo") to { if (lessonIndex >= steps.size - 1) endLesson() else lessonStep(lessonIndex + 1) },
        )
        buttons.forEachIndexed { i, (label, action) ->
            actions.addView(ui.button(label, if (steps == null) i == 0 else i == 1, action), LinearLayout.LayoutParams(0, context.dp(46), 1f).apply { if (i > 0) marginStart = context.dp(8) })
        }
    }
    private fun replay() {
        if (playing) return
        val event = latest ?: exampleTrace().also { latest = it; render() }
        playAll(traceSteps(event), colorFor(event))
    }
    // Modo aula: o pacote anda um salto por vez, no ritmo de quem está explicando.
    private fun startLesson() {
        val event = latest ?: exampleTrace().also { latest = it; render() }
        network.stop(); playing = false
        lesson = traceSteps(event); lessonStep(0)
    }
    private fun lessonStep(index: Int) {
        val steps = lesson ?: return
        lessonIndex = index.coerceIn(0, steps.size - 1)
        val step = steps[lessonIndex]
        showStep(step, "Passo ${lessonIndex + 1} de ${steps.size}")
        latest?.let { network.play(step.hops, colorFor(it)) {} }
        renderActions()
    }
    private fun endLesson() { lesson = null; idleCaption(); renderActions() }
    private fun idleCaption() {
        stepTag.text = "COMO LER O DESENHO"
        caption.text = "Cada quadro é um processo. As bolinhas que andam são mensagens. Toque num quadro para saber o que ele faz."
    }
    private fun section(title: String) = ui.text(title, 17f, Palette.ink, true).apply { setPadding(0, context.dp(28), 0, context.dp(6)) }
    private fun icon(name: String, size: Int, color: Int) = ImageView(context).apply {
        setImageDrawable(IconDrawable(name, color)); layoutParams = LinearLayout.LayoutParams(context.dp(size), context.dp(size))
    }
    private fun legend(): CharSequence = SpannableStringBuilder().apply {
        listOf(Color.parseColor("#16A34A") to "no ar", Palette.danger to "fora do ar", Color.parseColor("#D85A30") to "relato", Color.parseColor("#378ADD") to "leitura do painel")
            .forEachIndexed { i, (color, label) ->
                if (i > 0) append("    ")
                val start = length; append("●"); setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); append(" $label")
            }
    }

    private fun explainNode(key: String) {
        val info = nodeExplanations[key] ?: return
        val body = ui.column()
        live(key)?.let { (text, color) -> body.addView(ui.text(text, 14f, color, true).apply { setPadding(0, context.dp(4), 0, context.dp(10)) }) }
        body.addView(ui.text(info.what, 15f, Palette.ink).apply { setLineSpacing(0f, 1.2f) })
        info.ifDown?.let { body.addView(ui.text("Se cair: $it", 14f, Palette.muted).apply { setPadding(0, context.dp(12), 0, 0); setLineSpacing(0f, 1.2f) }) }
        body.addView(ui.space(14))
        body.addView(ui.text("Conceito: ${info.concept}", 13f, Palette.accent, true))
        body.addView(ui.text(conceptExplanations[info.concept].orEmpty(), 13f, Palette.muted).apply { setPadding(0, context.dp(4), 0, context.dp(8)) })
        ui.sheet(info.title, body).show()
    }
    private fun live(key: String): Pair<String, Int>? {
        val s = state ?: return null
        val node = when (key) {
            "api1", "api2" -> s.nodes.firstOrNull { it.role == "api" && replicaKey(it.node) == key }
            "wk" -> s.nodes.firstOrNull { it.role == "worker" }
            "col" -> s.nodes.firstOrNull { it.role == "collector" }
            else -> null
        }
        return when {
            key == "mq" -> if (s.brokerUp) "● Conectado às réplicas" to Palette.accent else "● Fora do ar · ${s.pending} evento(s) esperando na outbox" to Palette.danger
            key == "db" -> "● Respondendo" to Palette.accent
            key == "semil" -> when (s.nodes.firstOrNull { it.role == "collector" }?.collectorOk) { false -> "● Última leitura falhou" to Palette.warning; true -> "● Última leitura OK" to Palette.accent; null -> null }
            node == null -> null
            node.alive -> "● Vivo · último sinal há ${node.ageMs / 1000} s" + (node.clients?.let { " · $it celular(es)" } ?: "") to Palette.accent
            else -> "● Sem sinal há ${node.ageMs / 1000} s" to Palette.danger
        }
    }
    private fun explainConcept(concept: String) {
        val body = ui.column()
        body.addView(ui.text(conceptExplanations[concept].orEmpty(), 15f, Palette.ink).apply { setLineSpacing(0f, 1.2f); setPadding(0, context.dp(4), 0, context.dp(8)) })
        if (concept == "Idempotência") state?.let { body.addView(ui.text("Reenvios ignorados até agora: ${it.replays}", 13f, Palette.muted)) }
        ui.sheet(concept, body).show()
    }
    private fun ago(value: String) = runCatching {
        val seconds = Duration.between(Instant.parse(value), Instant.now()).seconds.coerceAtLeast(0)
        if (seconds < 60) "há $seconds s" else if (seconds < 3600) "há ${seconds / 60} min" else "há ${seconds / 3600} h"
    }.getOrDefault("")
}
