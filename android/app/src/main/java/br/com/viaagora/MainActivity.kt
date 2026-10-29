package br.com.viaagora

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.*
import android.os.*
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.time.Duration
import java.time.Instant

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("viaagora_private", MODE_PRIVATE) }
    private val state = AppState()
    private lateinit var ui: Ui
    private lateinit var mapPanel: MapPanel
    private lateinit var statusText: TextView
    private lateinit var tabs: List<TextView>
    private lateinit var crossingList: LinearLayout
    private lateinit var reportList: LinearLayout
    private lateinit var pages: List<View>
    private lateinit var bastidores: BastidoresPage
    private lateinit var fab: View
    private var remote: RemoteRepository? = null
    private var data = Snapshot(crossingCatalog, emptyList(), SourceInfo(null, false, null, null))
    private var tab = 0
    private var reportCrossing: String? = null
    private var liveConnected = false
    private var connectionError = false
    private var pendingLocation: (() -> Unit)? = null
    private var activeLocationListener: LocationListener? = null
    private val handler = Handler(Looper.getMainLooper())
    // Busca de novo a cada 30 s: se o canal ao vivo cair (app em segundo plano, troca de rede), os relatos ainda chegam.
    private val ticker = object : Runnable { override fun run() { refresh(); handler.postDelayed(this, 30_000) } }
    private var knownReports: Set<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.WHITE
        WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true }
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.page) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(root)
        val screen = ui.column()
        root.addView(screen, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val header = ui.row().apply { setPadding(dp(20), dp(18), dp(8), dp(12)) }
        val titles = ui.column()
        titles.addView(ui.text("Balsas agora", 28f, Palette.ink, true))
        statusText = ui.text("", 13f, Palette.muted).apply { setPadding(0, dp(6), 0, 0); setOnClickListener { refresh() } }
        titles.addView(statusText)
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(ImageView(this).apply { setImageDrawable(IconDrawable("sliders", Palette.ink)); setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "Ajustes"; isClickable = true; setOnClickListener { configure() } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        screen.addView(header)

        val content = FrameLayout(this)
        screen.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        crossingList = ui.column().apply { setPadding(dp(16), dp(4), dp(16), dp(96)) }
        reportList = ui.column().apply { setPadding(dp(16), dp(4), dp(16), dp(96)) }
        mapPanel = MapPanel(this, savedInstanceState, ::openMarker, { lat, lng -> report(null, lat, lng) }, ::locate)
        bastidores = BastidoresPage(this, ui)
        pages = listOf(ScrollView(this).apply { addView(crossingList) }, mapPanel, ScrollView(this).apply { addView(reportList) }, bastidores.view)
        pages.forEach { content.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)) }

        val nav = ui.row().apply { setBackgroundColor(Color.WHITE) }
        tabs = listOf("Travessias", "Mapa", "Relatos", "Bastidores").mapIndexed { index, label ->
            ui.text(label, 13f, Palette.muted, true).apply { gravity = Gravity.CENTER; isClickable = true; contentDescription = label; setOnClickListener { tab = index; render() } }
                .also { nav.addView(it, LinearLayout.LayoutParams(0, dp(60), 1f)) }
        }
        screen.addView(View(this).apply { setBackgroundColor(Palette.line) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        screen.addView(nav)

        fab = ui.text("+", 30f, Color.WHITE).apply {
            gravity = Gravity.CENTER; background = ui.rounded(Palette.accent, 30); elevation = dp(6).toFloat()
            contentDescription = "Relatar fila ou problema"; isClickable = true
            setOnClickListener { if (tab == 1) { val p = mapPanel.center(); report(null, p.first, p.second) } else report(null) }
        }
        root.addView(fab, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(20), dp(80)) })

        // Abre no servidor oficial; quem escolheu outro servidor nos Ajustes continua nele.
        val saved = prefs.getString("last_server", "").orEmpty().ifBlank { PRODUCTION_SERVER }
        runCatching { activate(saved) }.onFailure { activate(PRODUCTION_SERVER) }
    }

    private fun activate(server: String) {
        remote?.close(); remote = null
        state.select(server)
        liveConnected = false; connectionError = false; knownReports = null
        bastidores.source = { ok, fail -> remote?.system(ok, fail) }
        bastidores.server = state.server
        bastidores.reset()
        val repo = RemoteRepository(prefs, state.server); remote = repo
        data = repo.cached() ?: Snapshot(crossingCatalog, emptyList(), SourceInfo(null, false, null, null)); render()
        val ticket = state.generation
        repo.connect({ if (state.accepts(ticket)) refresh() }, { connected -> if (state.accepts(ticket)) { liveConnected = connected; render() } },
            { type, replica, traced -> if (state.accepts(ticket)) bastidores.onLiveEvent(type, replica, traced) })
        refresh()
    }
    private fun refresh() {
        val ticket = state.generation
        remote?.fetch({ snapshot -> if (state.accepts(ticket)) { connectionError = false; update(snapshot) } },
            { message -> if (state.accepts(ticket)) { connectionError = true; render(); toast(message) } })
    }
    private fun update(snapshot: Snapshot) {
        data = snapshot
        // Relato novo de outra pessoa: avisa na hora (na primeira carga só memoriza o que já existia).
        val ids = snapshot.reports.map { it.id }.toSet()
        knownReports?.let { known ->
            snapshot.reports.firstOrNull { it.id !in known && it.status == "active" && it.authorId != authorId() }
                ?.let { toast("Novo relato · ${reportTitle(it)}") }
        }
        knownReports = ids
        render()
    }

    // Uma única linha de status; vermelho/âmbar só quando há algo errado.
    private fun renderStatus() {
        val source = data.source
        val (text, color) = when {
            connectionError -> (if (source.lastSuccessAt != null) "Sem conexão · dados de ${relativeTime(source.lastSuccessAt)} · toque para tentar"
                else "Sem conexão · toque para tentar de novo") to Palette.danger
            source.lastSuccessAt == null -> "Aguardando o painel oficial" to Palette.muted
            !source.available -> "Painel oficial fora do ar · último dado ${relativeTime(source.lastSuccessAt)}" to Palette.warning
            liveConnected -> "● Ao vivo · atualizado ${relativeTime(source.lastSuccessAt)}" to Palette.accent
            else -> "Atualizado ${relativeTime(source.lastSuccessAt)}" to Palette.muted
        }
        statusText.text = text; statusText.setTextColor(color)
    }
    private fun render() {
        renderStatus()
        tabs.forEachIndexed { index, view -> view.setTextColor(if (index == tab) Palette.accent else Palette.muted) }
        pages.forEachIndexed { index, view -> view.visibility = if (index == tab) View.VISIBLE else View.GONE }
        fab.visibility = if (tab == 3) View.GONE else View.VISIBLE
        if (tab == 3) bastidores.show() else bastidores.hide()
        crossingList.removeAllViews()
        data.crossings.forEach { crossingList.addView(crossingCard(it)); crossingList.addView(ui.space(12)) }
        val active = data.reports.filter { it.status == "active" }
        mapPanel.update(data.crossings.flatMap { c -> listOf("A", "B").map { side ->
            val t = c.terminal(side); MapMarker("terminal:${c.id}:$side", t.latitude, t.longitude, waitLevel(wait(c, side), c.status?.operating ?: true).color, true) } } +
            active.map { MapMarker("report:${it.id}", it.latitude, it.longitude, categoryInfo(it.category).color, false) },
            data.crossings.map { MapRoute(it.route, if (it.stopped) "#DC2626" else "#0B7A8A") })
        renderReports()
    }
    private fun wait(crossing: Crossing, side: String) = crossing.status?.let { if (side == "A") it.waitA else it.waitB }
    private fun levelColor(c: Crossing, side: String) = Color.parseColor(waitLevel(wait(c, side), c.status?.operating ?: true).color)
    // Com a travessia parada, mostrar "0 min" engana: aparece "Parada" no lugar dos minutos.
    private fun sideValue(c: Crossing, side: String, size: Int = 32): CharSequence =
        if (c.stopped) ui.big("Parada", Palette.danger, size * 3 / 4) else ui.minutes(wait(c, side), levelColor(c, side), size)
    private fun recentReports(c: Crossing) = data.reports.filter { it.crossingId == c.id && it.status == "active" }
    private fun stopReportsText(c: Crossing) = "⚠ ${c.stopReports} relato${if (c.stopReports > 1) "s" else ""} de travessia parada"

    private fun crossingCard(c: Crossing): View {
        val s = c.status
        val card = ui.column().apply {
            setPadding(dp(20), dp(18), dp(20), dp(18)); background = ui.rounded(Color.WHITE, 20)
            isClickable = true; isFocusable = true; setOnClickListener { crossingDetails(c) }
        }
        val title = ui.row()
        title.addView(ui.text(c.name, 17f, Palette.ink, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (s != null) title.addView(ui.text(if (s.operating) "⛴ ${s.boats}" else "⛴ 0", 13f, if (s.operating) Palette.muted else Palette.danger))
        card.addView(title); card.addView(ui.space(14))
        val sides = ui.row().apply { gravity = Gravity.TOP }
        listOf("A", "B").forEachIndexed { index, side ->
            if (index > 0) sides.addView(View(this).apply { setBackgroundColor(Palette.line) }, LinearLayout.LayoutParams(dp(1), dp(44)).apply { marginEnd = dp(16) })
            val box = ui.column()
            box.addView(ui.text(c.terminal(side).name, 13f, Palette.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            box.addView(ui.space(4))
            box.addView(ui.text(sideValue(c, side), 15f, Palette.ink, true))
            (if (side == "A") c.communityA else c.communityB)?.let { box.addView(ui.text("~${it.waitMinutes} min pela comunidade", 12f, Palette.muted).apply { setPadding(0, dp(4), 0, 0) }) }
            sides.addView(box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        card.addView(sides)
        val (message, color) = when {
            s == null -> "Sem leitura do painel ainda" to Palette.muted
            c.stopped -> (s.notice?.takeIf { it.isNotBlank() } ?: "Sem embarcação em operação") to Palette.danger
            s.stale -> "Dado atrasado · ${relativeTime(s.collectedAt)}" to Palette.warning
            c.stopReports > 0 -> stopReportsText(c) to Palette.danger
            !s.notice.isNullOrBlank() -> "⚠ ${s.notice}" to Palette.warning
            else -> null to Palette.muted
        }
        if (message != null) card.addView(ui.text(message, 13f, color).apply { setPadding(0, dp(12), 0, 0); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        recentReports(c).takeIf { it.isNotEmpty() }?.let { recent ->
            val last = recent.first()
            card.addView(ui.text("💬 ${recent.size} relato${if (recent.size > 1) "s" else ""} · ${categoryInfo(last.category).label} ${relativeTime(last.createdAt)}",
                13f, Palette.accent).apply { setPadding(0, dp(10), 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }
        card.contentDescription = if (c.stopped) "${c.name}: travessia parada. ${s?.notice.orEmpty()}"
            else "${c.name}. ${c.pointA.name}: ${wait(c, "A") ?: "sem dado"} minutos. ${c.pointB.name}: ${wait(c, "B") ?: "sem dado"} minutos."
        return card
    }
    private fun crossingDetails(c: Crossing, side: String? = null) {
        val s = c.status
        val body = ui.column()
        body.addView(ui.text(kindLabel(c.kind), 13f, Palette.muted))
        body.addView(ui.space(18))
        val sides = ui.row()
        listOf("A", "B").forEach { sd ->
            val box = ui.column().apply { setPadding(dp(14), dp(12), dp(14), dp(12)); background = ui.rounded(if (sd == side) Palette.soft else Palette.page, 14) }
            box.addView(ui.text(c.terminal(sd).name, 13f, Palette.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            box.addView(ui.space(4))
            box.addView(ui.text(sideValue(c, sd, 28), 15f, Palette.ink, true))
            if (!c.stopped) box.addView(ui.text(waitLevel(wait(c, sd), s?.operating ?: true).label, 12f, levelColor(c, sd)).apply { setPadding(0, dp(4), 0, 0) })
            (if (sd == "A") c.communityA else c.communityB)?.let { box.addView(ui.text("~${it.waitMinutes} min · ${it.reports} relato${if (it.reports > 1) "s" else ""}", 12f, Palette.muted).apply { setPadding(0, dp(4), 0, 0) }) }
            sides.addView(box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (sd == "B") marginStart = dp(10) })
        }
        body.addView(sides)
        if (s != null) {
            body.addView(ui.space(16))
            listOfNotNull(
                if (c.stopped) "⛔ Travessia parada pelo painel oficial" else null,
                if (c.stopReports > 0) stopReportsText(c) else null,
                if (s.operating) "${s.boats} ${if (s.boats == 1) "embarcação" else "embarcações"} em operação" else "Sem embarcação em operação",
                s.weatherA?.takeIf { it.isNotBlank() }?.let { "Tempo: $it" },
                s.notice?.takeIf { it.isNotBlank() }?.let { "⚠ $it" },
                "Painel oficial · ${relativeTime(s.collectedAt)}",
            ).forEach { line -> body.addView(ui.text(line, 14f, when { line.startsWith("⛔") || line.contains("travessia parada") -> Palette.danger; line.startsWith("⚠") -> Palette.warning; else -> Palette.ink }).apply { setPadding(0, dp(5), 0, dp(5)) }) }
        }
        val recent = recentReports(c)
        lateinit var sheet: android.app.Dialog
        if (recent.isNotEmpty()) {
            body.addView(ui.label("Relatos recentes"))
            recent.take(3).forEach { item -> body.addView(reportCard(item).apply { setOnClickListener { sheet.dismiss(); reportDetails(item) } }); body.addView(ui.space(6)) }
        }
        body.addView(ui.space(20))
        sheet = ui.sheet(c.name, body)
        val actions = ui.row()
        listOf("Melhor horário" to { sheet.dismiss(); history(c) }, "Relatar" to { sheet.dismiss(); report(c.id, initialSide = side ?: "A") })
            .forEachIndexed { index, (label, action) ->
                actions.addView(ui.button(label, index == 1, action), LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (index > 0) marginStart = dp(8) })
            }
        body.addView(actions)
        sheet.show()
    }

    private fun renderReports() {
        reportList.removeAllViews()
        val filters = listOf<String?>(null) + data.crossings.map { it.id }
        val labels = listOf("Todas") + data.crossings.map { it.name }
        reportList.addView(Choice(ui, labels, filters.indexOf(reportCrossing).coerceAtLeast(0)) { reportCrossing = filters[it]; render() }.view)
        reportList.addView(ui.space(14))
        val visible = filterReports(data.reports, reportCrossing, false)
        if (visible.isEmpty()) reportList.addView(ui.text("Nenhum relato recente.\nEstá na fila? Toque em + e conte quanto está esperando.", 15f, Palette.muted).apply {
            gravity = Gravity.CENTER; setPadding(dp(24), dp(48), dp(24), dp(24)) })
        visible.forEach { reportList.addView(reportCard(it)); reportList.addView(ui.space(10)) }
    }
    private fun crossingOf(id: String) = data.crossings.firstOrNull { it.id == id } ?: crossingCatalog.first { it.id == id }
    // Onde foi o relato: a travessia e o lado da fila (ex.: "Santos ⇄ Guarujá · lado Santos").
    private fun reportPlace(item: Report) = crossingOf(item.crossingId).let { "${it.name} · lado ${it.terminal(item.side).name}" }
    private fun reportTitle(item: Report) = "${categoryInfo(item.category).label} · ${reportPlace(item)}"
    private fun reportCard(item: Report): View {
        val info = categoryInfo(item.category); val color = Color.parseColor(info.color)
        val card = ui.row().apply { setPadding(dp(16), dp(14), dp(16), dp(14)); background = ui.rounded(Color.WHITE, 18); isClickable = true; isFocusable = true; setOnClickListener { reportDetails(item) } }
        card.addView(ui.text(info.symbol, 18f, color, true).apply { gravity = Gravity.CENTER; background = ui.rounded(Palette.page, 20) },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
        val text = ui.column()
        val head = ui.row()
        head.addView(ui.text(info.label, 15f, Palette.ink, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (item.authorId == authorId()) head.addView(ui.text("Seu relato", 11f, Palette.accent, true).apply {
            background = ui.rounded(Palette.soft, 10); setPadding(dp(8), dp(3), dp(8), dp(3)) })
        text.addView(head)
        text.addView(ui.text(reportPlace(item), 14f, Palette.ink).apply { setPadding(0, dp(4), 0, 0) })
        text.addView(ui.text(listOfNotNull(item.waitMinutes?.let { "Espera de $it min" }, "enviado ${relativeTime(item.createdAt)}").joinToString(" · ")
            .replaceFirstChar { it.uppercase() }, 13f, Palette.muted).apply { setPadding(0, dp(4), 0, 0) })
        card.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.contentDescription = reportTitle(item)
        return card
    }
    private fun reportDetails(item: Report) {
        val body = ui.column()
        val crossing = crossingOf(item.crossingId)
        listOf("Travessia" to crossing.name, "Lado da fila" to crossing.terminal(item.side).name,
            "Espera informada" to (item.waitMinutes?.let { "$it min" } ?: "não informada"),
            (if (item.status == "active") "Enviado" else "Encerrado") to relativeTime(item.createdAt),
        ).forEach { (label, value) ->
            val row = ui.row().apply { setPadding(0, dp(7), 0, dp(7)) }
            row.addView(ui.text(label, 14f, Palette.muted), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ui.text(value, 14f, Palette.ink, true))
            body.addView(row)
        }
        item.description.takeIf { it.isNotBlank() }?.let {
            body.addView(ui.text("“$it”", 15f, Palette.ink).apply { setPadding(dp(14), dp(12), dp(14), dp(12)); background = ui.rounded(Palette.soft, 12)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) } })
        }
        body.addView(ui.text("Informação colaborativa, sem confirmação oficial.", 13f, Palette.muted).apply { setPadding(0, dp(14), 0, 0) })
        body.addView(ui.space(18))
        val sheet = ui.sheet(categoryInfo(item.category).label, body)
        val actions = ui.row()
        actions.addView(ui.button("Ver no mapa") { sheet.dismiss(); tab = 1; render(); mapPanel.focus(item.latitude, item.longitude) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        if (item.status == "active" && item.authorId == authorId()) actions.addView(ui.button("Encerrar", true) {
            sheet.dismiss()
            val ticket = state.generation
            remote?.resolve(item.id, { if (state.accepts(ticket)) { refresh(); toast("Relato encerrado.") } }, ::failure)
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        body.addView(actions)
        sheet.show()
    }
    private fun openMarker(id: String) {
        val parts = id.split(":")
        if (parts[0] == "report") data.reports.firstOrNull { it.id == parts[1] }?.let(::reportDetails)
        else crossingDetails(crossingOf(parts[1]), parts[2])
    }

    private fun history(c: Crossing) {
        val show = { h: History ->
            val body = ui.column()
            if (h.hourly.isEmpty()) body.addView(ui.text("Ainda não há leituras suficientes. Volte mais tarde.", 15f, Palette.muted).apply { setPadding(0, dp(12), 0, dp(12)) })
            else {
                body.addView(ui.text("Espera média por hora, últimos 7 dias", 13f, Palette.muted))
                body.addView(ui.space(16)); body.addView(HistoryChart(this, h)); body.addView(ui.space(16))
                listOf("A" to h.bestA, "B" to h.bestB).forEach { (side, hour) ->
                    if (hour == null) return@forEach
                    val avg = h.hourly.first { it.hour == hour }.let { if (side == "A") it.waitA else it.waitB }
                    val line = ui.row().apply { setPadding(0, dp(6), 0, dp(6)) }
                    line.addView(ui.text("■", 14f, if (side == "A") Palette.navy else Palette.accent).apply { setPadding(0, 0, dp(10), 0) })
                    line.addView(ui.text("Saindo de ${c.terminal(side).name}", 15f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    line.addView(ui.text("${hour}h · ~$avg min", 15f, Color.parseColor(WaitLevel.LOW.color), true))
                    body.addView(line)
                }
            }
            ui.sheet("Melhor horário", body).show()
        }
        val ticket = state.generation
        remote?.history(c.id, { if (state.accepts(ticket)) show(it) }, ::failure)
    }
    private fun report(crossingId: String?, lat: Double? = null, lng: Double? = null, initialSide: String = "A") {
        val body = ui.column()
        var crossing = crossingCatalog.firstOrNull { it.id == crossingId } ?: crossingCatalog.first()
        lateinit var side: Choice
        body.addView(ui.label("Travessia"))
        body.addView(Choice(ui, crossingCatalog.map { it.name }, crossingCatalog.indexOf(crossing)) {
            crossing = crossingCatalog[it]; side.relabel(listOf("Em ${crossing.pointA.name}", "Em ${crossing.pointB.name}"))
        }.view)
        body.addView(ui.label("Onde você está"))
        side = Choice(ui, listOf("Em ${crossing.pointA.name}", "Em ${crossing.pointB.name}"), if (initialSide == "B") 1 else 0)
        body.addView(side.view)
        body.addView(ui.label("O que está acontecendo"))
        val category = Choice(ui, categories.map { it.label })
        body.addView(category.view)
        body.addView(ui.label("Detalhes (opcional)"))
        val waitInput = ui.input("Minutos de espera").apply { inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(InputFilter.LengthFilter(3)) }
        body.addView(waitInput); body.addView(ui.space(8))
        val description = ui.input("Ex.: a fila chega até o canal 6").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; filters = arrayOf(InputFilter.LengthFilter(400)) }
        body.addView(description)
        if (lat != null) body.addView(ui.text("📍 Ponto escolhido no mapa", 13f, Palette.muted).apply { setPadding(0, dp(12), 0, 0) })
        body.addView(ui.space(24))
        val sheet = ui.sheet("Novo relato", body)
        body.addView(ui.button("Publicar", true) {
            val sideKey = if (side.selected == 1) "B" else "A"
            val terminal = crossing.terminal(sideKey)
            val waitText = waitInput.text.toString().trim()
            val wait = if (waitText.isEmpty()) null else waitText.toIntOrNull() ?: -1
            val point = if (lat != null && lng != null) lat to lng else terminal.latitude to terminal.longitude
            val cat = categories[category.selected].key
            val error = validateReport(crossing.id, sideKey, cat, wait, description.text.toString(), point.first, point.second)
            if (error != null) { toast(error); return@button }
            val input = ReportInput(crossing.id, sideKey, cat, wait, description.text.toString(), point.first, point.second)
            val ticket = state.generation
            remote?.create(input, { if (state.accepts(ticket)) { sheet.dismiss(); tab = 2; refresh(); toast("Relato publicado.") } },
                { message -> if (state.accepts(ticket)) toast(message) })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        sheet.show()
    }
    // Ajustes: o normal é o servidor oficial. "Outro servidor" é para quem roda o sistema no próprio computador.
    private fun configure() {
        val body = ui.column()
        body.addView(ui.text("De onde o app busca os dados", 13f, Palette.muted))
        body.addView(ui.space(14))
        val current = prefs.getString("last_server", PRODUCTION_SERVER).orEmpty()
        var custom = current != PRODUCTION_SERVER
        val address = ui.input("http://192.168.0.10:8080", if (custom) current else "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; isSingleLine = true }
        val error = ui.text("", 13f, Palette.danger).apply { setPadding(dp(4), dp(8), 0, 0); visibility = View.GONE }
        val options = listOf(
            Triple("servers", "Servidor oficial", "sedda.com.br · dados reais e relatos de todos"),
            Triple("server", "Outro servidor", "O sistema rodando no seu computador (Rodar local)"),
        ).mapIndexed { index, (icon, title, detail) ->
            ui.row().apply {
                setPadding(dp(14), dp(14), dp(14), dp(14)); isClickable = true; contentDescription = title
                addView(ImageView(this@MainActivity).apply { setImageDrawable(IconDrawable(icon, Palette.accent)) }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) })
                addView(ui.column().apply { addView(ui.text(title, 15f, Palette.ink, true)); addView(ui.text(detail, 13f, Palette.muted).apply { setPadding(0, dp(3), 0, 0) }) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }.also { row -> body.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }) }
        }
        fun paint() {
            options.forEachIndexed { i, row -> val on = (i == 1) == custom; row.background = ui.rounded(if (on) Palette.soft else Color.WHITE, 14, if (on) Palette.accent else Palette.line) }
            address.visibility = if (custom) View.VISIBLE else View.GONE; error.visibility = View.GONE
        }
        options.forEachIndexed { i, row -> row.setOnClickListener { custom = i == 1; paint() } }
        body.addView(address); body.addView(error); paint()
        body.addView(ui.space(12))
        body.addView(ui.text("Dados: painel das Travessias Litorâneas (SEMIL-SP). Relatos são colaborativos. Mapa © OpenStreetMap.", 12f, Palette.muted))
        body.addView(ui.space(20))
        val sheet = ui.sheet("Ajustes", body)
        body.addView(ui.button("Aplicar", true) {
            val url = if (custom) runCatching { normalizeServer(address.text.toString()) }
                .getOrElse { error.text = it.message; error.visibility = View.VISIBLE; return@button } else PRODUCTION_SERVER
            prefs.edit().putString("last_server", url).apply()
            sheet.dismiss(); activate(url)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        sheet.show()
    }
    private fun authorId() = remote?.authorId.orEmpty()
    private fun failure(message: String) { connectionError = true; render(); toast(message) }
    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
    private fun relativeTime(value: String?): String = runCatching {
        val minutes = Duration.between(Instant.parse(value), Instant.now()).toMinutes().coerceAtLeast(0)
        if (minutes < 1) "agora" else if (minutes < 60) "há $minutes min" else if (minutes < 1440) "há ${minutes / 60} h" else "há ${minutes / 1440} dias"
    }.getOrDefault("—")

    @Suppress("MissingPermission", "DEPRECATION")
    private fun locate() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingLocation = { locate() }; requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 41); return
        }
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        val known = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { isRecentLocation((SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000L) }.maxByOrNull { it.time }
        if (known != null) { mapPanel.focus(known.latitude, known.longitude); return }
        if (providers.isEmpty()) { toast("Ative a localização ou escolha um ponto no mapa."); return }
        toast("Buscando sua posição…")
        activeLocationListener?.let { manager.removeUpdates(it) }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { manager.removeUpdates(this); activeLocationListener = null; mapPanel.focus(location.latitude, location.longitude) }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Android") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        activeLocationListener = listener
        runCatching { manager.requestLocationUpdates(providers.first(), 0L, 0f, listener, Looper.getMainLooper()) }.onFailure { toast("Não foi possível obter a posição.") }
        handler.postDelayed({ if (activeLocationListener === listener) { manager.removeUpdates(listener); activeLocationListener = null; toast("Posição indisponível.") } }, 10000)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41) { if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) pendingLocation?.invoke() else toast("Sem problema: escolha o ponto no mapa."); pendingLocation = null }
    }
    override fun onStart() { super.onStart(); mapPanel.start(); handler.postDelayed(ticker, 30_000); if (tab == 3) bastidores.show() }
    override fun onResume() { super.onResume(); mapPanel.resume(); refresh() }
    override fun onPause() { mapPanel.pause(); super.onPause() }
    override fun onStop() { handler.removeCallbacks(ticker); bastidores.hide(); mapPanel.stop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapPanel.lowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapPanel.save(outState) }
    override fun onDestroy() { remote?.close(); handler.removeCallbacksAndMessages(null); activeLocationListener?.let { runCatching { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(it) } }; mapPanel.destroy(); super.onDestroy() }
}
