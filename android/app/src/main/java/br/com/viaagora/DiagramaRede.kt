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
class DiagramaRede(context: Context, private val aoTocarProcesso: (String) -> Unit) : View(context) {
    enum class Saude { DESCONHECIDA, NO_AR, FORA_DO_AR }
    private data class Quadro(val x: Float, val y: Float, val rotulo: String)
    private val processos = linkedMapOf(
        "semil" to Quadro(46f, 62f, "Site SEMIL"),
        "cel1" to Quadro(145f, 62f, "Celulares"),
        "cel2" to Quadro(255f, 62f, "Celulares"),
        "nginx" to Quadro(200f, 160f, "Nginx"),
        "api1" to Quadro(110f, 252f, "API 1"),
        "mq" to Quadro(200f, 252f, "RabbitMQ"),
        "api2" to Quadro(290f, 252f, "API 2"),
        "wk" to Quadro(200f, 338f, "Worker"),
        "col" to Quadro(46f, 424f, "Coletor"),
        "db" to Quadro(200f, 424f, "PostgreSQL"),
    )
    private val metade = 34f to 28f
    private val linhas = listOf(
        linha(46f, 90f, 46f, 396f), linha(145f, 90f, 180f, 132f), linha(255f, 90f, 220f, 132f),
        linha(180f, 188f, 125f, 224f), linha(220f, 188f, 275f, 224f), linha(166f, 252f, 144f, 252f), linha(234f, 252f, 256f, 252f),
        linha(200f, 310f, 200f, 280f), linha(200f, 396f, 200f, 366f), linha(110f, 280f, 170f, 396f), linha(290f, 280f, 230f, 396f), linha(80f, 424f, 166f, 424f),
    )
    // Caminho que cada mensagem percorre; celular e API conversam sempre passando pelo Nginx.
    private val rotas: Map<Pair<String, String>, Path> = mapOf(
        ("semil" to "col") to linhas[0], ("col" to "db") to linhas[11], ("db" to "wk") to linhas[8], ("wk" to "mq") to linhas[7],
        ("api1" to "db") to linhas[9], ("api2" to "db") to linhas[10], ("mq" to "api1") to linhas[5], ("mq" to "api2") to linhas[6],
        ("cel1" to "api1") to Path().apply { moveTo(145f, 90f); lineTo(200f, 160f); lineTo(125f, 224f) },
        ("cel2" to "api2") to Path().apply { moveTo(255f, 90f); lineTo(200f, 160f); lineTo(275f, 224f) },
    )
    private fun linha(x1: Float, y1: Float, x2: Float, y2: Float) = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }

    var saude: Map<String, Saude> = emptyMap(); set(valor) { field = valor; invalidate() }
    var voce: String? = null; set(valor) { field = valor; invalidate() }
    var rotulosCelulares: Map<String, String> = emptyMap(); set(valor) { field = valor; invalidate() }
    var rotuloServidor = "VPS · SEDDA.COM.BR"; set(valor) { field = valor; invalidate() }

    private class Pacote(val medida: PathMeasure, val invertido: Boolean, val color: Int)
    private var pacotes: List<Pacote> = emptyList()
    private var inicio = 0L
    private var aoTerminar: (() -> Unit)? = null
    private val duracao = 800L

    private val density = resources.displayMetrics.density
    private val tintaLigacao = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#CBD5E1"); strokeCap = Paint.Cap.ROUND }
    private val zona = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bordaZona = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Paleta.linha }
    private val preenchimento = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borda = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val legenda = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Paleta.apagado; letterSpacing = 0.08f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val bolinha = Paint(Paint.ANTI_ALIAS_FLAG)
    private val verde = Color.parseColor("#16A34A")

    // Anima uma mensagem em cada ligação ao mesmo tempo (ex.: o broker entregando para as duas réplicas).
    fun animar(saltos: List<Pair<String, String>>, color: Int, concluir: () -> Unit) {
        pacotes = saltos.mapNotNull { (from, to) ->
            val ida = rotas[from to to]; val volta = rotas[to to from]
            (ida ?: volta)?.let { Pacote(PathMeasure(it, false), ida == null, color) }
        }
        if (pacotes.isEmpty()) { concluir(); return }
        aoTerminar = concluir; inicio = SystemClock.uptimeMillis(); postInvalidateOnAnimation()
    }
    fun stop() { pacotes = emptyList(); aoTerminar = null; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 474f / 340f).toInt())
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = width / 340f
        val unidade = density / s
        canvas.save(); canvas.scale(s, s)
        // Duas regiões: a internet (fora do nosso controle) e a VPS (os processos do sistema).
        legenda.textSize = 10f * unidade
        canvas.drawText("INTERNET", 12f, 20f, legenda)
        zona.color = Paleta.fundo; bordaZona.strokeWidth = 1f * unidade
        val areaVps = RectF(4f, 108f, 336f, 466f)
        canvas.drawRoundRect(areaVps, 16f, 16f, zona); canvas.drawRoundRect(areaVps, 16f, 16f, bordaZona)
        Icones.draw(canvas, "servers", 22f, 124f, 13f, Paleta.apagado)
        canvas.drawText(rotuloServidor, 34f, 128f, legenda)

        tintaLigacao.strokeWidth = 1.6f * unidade
        linhas.forEach { canvas.drawPath(it, tintaLigacao) }

        text.textSize = 10.5f * unidade
        for ((key, n) in processos) {
            val estado = saude[key] ?: Saude.DESCONHECIDA
            val meu = voce?.let { key == it || key == chaveCelulares(it) } == true
            val caixa = RectF(n.x - metade.first, n.y - metade.second, n.x + metade.first, n.y + metade.second)
            preenchimento.color = if (estado == Saude.FORA_DO_AR) Color.parseColor("#FEF2F2") else Color.WHITE
            borda.color = when { estado == Saude.FORA_DO_AR -> Paleta.perigo; meu -> Paleta.destaque; else -> Paleta.linha }
            borda.strokeWidth = (if (meu) 2f else 1f) * unidade
            borda.pathEffect = if (estado == Saude.FORA_DO_AR) DashPathEffect(floatArrayOf(5f, 4f), 0f) else null
            canvas.drawRoundRect(caixa, 12f, 12f, preenchimento); canvas.drawRoundRect(caixa, 12f, 12f, borda)
            val tom = when (estado) { Saude.FORA_DO_AR -> Paleta.perigo; Saude.NO_AR -> Paleta.destaque; Saude.DESCONHECIDA -> Paleta.apagado }
            Icones.draw(canvas, iconeDe(key), n.x, n.y - 7f, 22f, tom)
            text.color = if (estado == Saude.FORA_DO_AR) Paleta.perigo else Paleta.tinta
            canvas.drawText(rotulosCelulares[key] ?: n.rotulo, n.x, n.y + 19f, text)
            // Sinal de vida: verde quando o processo respondeu há pouco, vermelho quando sumiu.
            if (estado != Saude.DESCONHECIDA) {
                bolinha.color = Color.WHITE; canvas.drawCircle(caixa.right - 7f, caixa.top + 7f, 5f, bolinha)
                bolinha.color = if (estado == Saude.NO_AR) verde else Paleta.perigo; canvas.drawCircle(caixa.right - 7f, caixa.top + 7f, 3.4f, bolinha)
            }
        }

        val k = ((SystemClock.uptimeMillis() - inicio).toFloat() / duracao).coerceIn(0f, 1f)
        val suavizado = k * k * (3 - 2 * k)
        val ponto = FloatArray(2)
        for (p in pacotes) {
            p.medida.getPosTan((if (p.invertido) 1f - suavizado else suavizado) * p.medida.length, ponto, null)
            bolinha.color = Color.WHITE; canvas.drawCircle(ponto[0], ponto[1], 8f, bolinha)
            bolinha.color = p.color; canvas.drawCircle(ponto[0], ponto[1], 6f, bolinha)
        }
        canvas.restore()
        if (pacotes.isNotEmpty()) {
            if (k < 1f) postInvalidateOnAnimation()
            else { pacotes = emptyList(); val concluir = aoTerminar; aoTerminar = null; invalidate(); concluir?.invoke() }
        }
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(evento: MotionEvent): Boolean {
        if (evento.action == MotionEvent.ACTION_DOWN) return true
        if (evento.action != MotionEvent.ACTION_UP) return false
        val s = width / 340f; val x = evento.x / s; val y = evento.y / s
        processos.entries.firstOrNull { (_, n) -> kotlin.math.abs(n.x - x) <= metade.first + 6f && kotlin.math.abs(n.y - y) <= metade.second + 6f }
            ?.let { (key, _) -> performClick(); aoTocarProcesso(key) }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    companion object {
        fun iconeDe(key: String) = when (key) {
            "semil" -> "globe"; "cel1", "cel2" -> "phone"; "nginx" -> "balancer"; "api1", "api2" -> "server"
            "mq" -> "queue"; "wk" -> "gear"; "col" -> "download"; "db" -> "database"; else -> "bolt"
        }
    }
}
