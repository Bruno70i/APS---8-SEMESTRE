package br.com.viaagora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

// Diagrama do sistema: a internet em cima e, dentro da VPS, os processos. Desenhado num espaço de 340 x 474
// e escalado para a largura da tela.
@SuppressLint("ViewConstructor")
class NetworkView(context: Context, private val onNode: (String) -> Unit) : View(context) {
    enum class Health { UNKNOWN, UP, DOWN }
    private data class Node(val x: Float, val y: Float, val label: String)
    private val nodes = linkedMapOf(
        "semil" to Node(46f, 62f, "Site SEMIL"),
        "cel1" to Node(145f, 62f, "Celulares"),
        "cel2" to Node(255f, 62f, "Celulares"),
        "nginx" to Node(200f, 160f, "Nginx"),
        "api1" to Node(110f, 252f, "API 1"),
        "mq" to Node(200f, 252f, "RabbitMQ"),
        "api2" to Node(290f, 252f, "API 2"),
        "wk" to Node(200f, 338f, "Worker"),
        "col" to Node(46f, 424f, "Coletor"),
        "db" to Node(200f, 424f, "PostgreSQL"),
    )
    private val half = 34f to 28f
    private val lines = listOf(
        line(46f, 90f, 46f, 396f), line(145f, 90f, 180f, 132f), line(255f, 90f, 220f, 132f),
        line(180f, 188f, 125f, 224f), line(220f, 188f, 275f, 224f), line(166f, 252f, 144f, 252f), line(234f, 252f, 256f, 252f),
        line(200f, 310f, 200f, 280f), line(200f, 396f, 200f, 366f), line(110f, 280f, 170f, 396f), line(290f, 280f, 230f, 396f), line(80f, 424f, 166f, 424f),
    )
    // Caminho que cada mensagem percorre; celular e API conversam sempre passando pelo Nginx.
    private val routes: Map<Pair<String, String>, Path> = mapOf(
        ("semil" to "col") to lines[0], ("col" to "db") to lines[11], ("db" to "wk") to lines[8], ("wk" to "mq") to lines[7],
        ("api1" to "db") to lines[9], ("api2" to "db") to lines[10], ("mq" to "api1") to lines[5], ("mq" to "api2") to lines[6],
        ("cel1" to "api1") to Path().apply { moveTo(145f, 90f); lineTo(200f, 160f); lineTo(125f, 224f) },
        ("cel2" to "api2") to Path().apply { moveTo(255f, 90f); lineTo(200f, 160f); lineTo(275f, 224f) },
    )
    private fun line(x1: Float, y1: Float, x2: Float, y2: Float) = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }

    var health: Map<String, Health> = emptyMap(); set(value) { field = value; invalidate() }
    var you: String? = null; set(value) { field = value; invalidate() }
    var phoneLabels: Map<String, String> = emptyMap(); set(value) { field = value; invalidate() }
    var serverLabel = "VPS · SEDDA.COM.BR"; set(value) { field = value; invalidate() }

    private class Packet(val measure: PathMeasure, val reverse: Boolean, val color: Int)
    private var packets: List<Packet> = emptyList()
    private var started = 0L
    private var onDone: (() -> Unit)? = null
    private val duration = 800L

    private val density = resources.displayMetrics.density
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#CBD5E1"); strokeCap = Paint.Cap.ROUND }
    private val zone = Paint(Paint.ANTI_ALIAS_FLAG)
    private val zoneBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Palette.line }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.muted; letterSpacing = 0.08f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val green = Color.parseColor("#16A34A")

    // Anima uma mensagem em cada ligação ao mesmo tempo (ex.: o broker entregando para as duas réplicas).
    fun play(hops: List<Pair<String, String>>, color: Int, done: () -> Unit) {
        packets = hops.mapNotNull { (from, to) ->
            val forward = routes[from to to]; val backward = routes[to to from]
            (forward ?: backward)?.let { Packet(PathMeasure(it, false), forward == null, color) }
        }
        if (packets.isEmpty()) { done(); return }
        onDone = done; started = SystemClock.uptimeMillis(); postInvalidateOnAnimation()
    }
    fun stop() { packets = emptyList(); onDone = null; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 474f / 340f).toInt())
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = width / 340f
        val unit = density / s
        canvas.save(); canvas.scale(s, s)
        // Duas regiões: a internet (fora do nosso controle) e a VPS (os processos do sistema).
        caption.textSize = 10f * unit
        canvas.drawText("INTERNET", 12f, 20f, caption)
        zone.color = Palette.page; zoneBorder.strokeWidth = 1f * unit
        val vps = RectF(4f, 108f, 336f, 466f)
        canvas.drawRoundRect(vps, 16f, 16f, zone); canvas.drawRoundRect(vps, 16f, 16f, zoneBorder)
        Icons.draw(canvas, "servers", 22f, 124f, 13f, Palette.muted)
        canvas.drawText(serverLabel, 34f, 128f, caption)

        linkPaint.strokeWidth = 1.6f * unit
        lines.forEach { canvas.drawPath(it, linkPaint) }

        text.textSize = 10.5f * unit
        for ((key, n) in nodes) {
            val state = health[key] ?: Health.UNKNOWN
            val mine = you?.let { key == it || key == phonesKey(it) } == true
            val box = RectF(n.x - half.first, n.y - half.second, n.x + half.first, n.y + half.second)
            fill.color = if (state == Health.DOWN) Color.parseColor("#FEF2F2") else Color.WHITE
            border.color = when { state == Health.DOWN -> Palette.danger; mine -> Palette.accent; else -> Palette.line }
            border.strokeWidth = (if (mine) 2f else 1f) * unit
            border.pathEffect = if (state == Health.DOWN) DashPathEffect(floatArrayOf(5f, 4f), 0f) else null
            canvas.drawRoundRect(box, 12f, 12f, fill); canvas.drawRoundRect(box, 12f, 12f, border)
            val tone = when (state) { Health.DOWN -> Palette.danger; Health.UP -> Palette.accent; Health.UNKNOWN -> Palette.muted }
            Icons.draw(canvas, iconOf(key), n.x, n.y - 7f, 22f, tone)
            text.color = if (state == Health.DOWN) Palette.danger else Palette.ink
            canvas.drawText(phoneLabels[key] ?: n.label, n.x, n.y + 19f, text)
            // Sinal de vida: verde quando o processo respondeu há pouco, vermelho quando sumiu.
            if (state != Health.UNKNOWN) {
                dot.color = Color.WHITE; canvas.drawCircle(box.right - 7f, box.top + 7f, 5f, dot)
                dot.color = if (state == Health.UP) green else Palette.danger; canvas.drawCircle(box.right - 7f, box.top + 7f, 3.4f, dot)
            }
        }

        val k = ((SystemClock.uptimeMillis() - started).toFloat() / duration).coerceIn(0f, 1f)
        val eased = k * k * (3 - 2 * k)
        val point = FloatArray(2)
        for (p in packets) {
            p.measure.getPosTan((if (p.reverse) 1f - eased else eased) * p.measure.length, point, null)
            dot.color = Color.WHITE; canvas.drawCircle(point[0], point[1], 8f, dot)
            dot.color = p.color; canvas.drawCircle(point[0], point[1], 6f, dot)
        }
        canvas.restore()
        if (packets.isNotEmpty()) {
            if (k < 1f) postInvalidateOnAnimation()
            else { packets = emptyList(); val done = onDone; onDone = null; invalidate(); done?.invoke() }
        }
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action != MotionEvent.ACTION_UP) return false
        val s = width / 340f; val x = event.x / s; val y = event.y / s
        nodes.entries.firstOrNull { (_, n) -> kotlin.math.abs(n.x - x) <= half.first + 6f && kotlin.math.abs(n.y - y) <= half.second + 6f }
            ?.let { (key, _) -> performClick(); onNode(key) }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    companion object {
        fun iconOf(key: String) = when (key) {
            "semil" -> "globe"; "cel1", "cel2" -> "phone"; "nginx" -> "balancer"; "api1", "api2" -> "server"
            "mq" -> "queue"; "wk" -> "gear"; "col" -> "download"; "db" -> "database"; else -> "bolt"
        }
    }
}
