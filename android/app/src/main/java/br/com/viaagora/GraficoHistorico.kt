package br.com.viaagora

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

// Barras com a espera média por hora do dia, uma cor para cada lado. A melhor hora (entre 6h e 22h) de cada lado fica em verde.
class GraficoHistorico(context: Context, private val historico: Historico) : View(context) {
    private val barraA = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Paleta.marinho }
    private val barraB = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Paleta.destaque }
    private val melhor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(NivelEspera.BAIXA.color) }
    private val rotulo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Paleta.apagado; textSize = context.dp(10).toFloat(); textAlign = Paint.Align.CENTER }
    private val grade = Paint().apply { color = Paleta.linha; strokeWidth = context.dp(1).toFloat() }
    init {
        contentDescription = "Espera média por hora. " + historico.porHora.joinToString("; ") { "${it.hora}h: ${it.esperaA} e ${it.esperaB} minutos" }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(170))
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bottom = height - context.dp(18f)
        val top = context.dp(8f)
        val max = (historico.porHora.maxOfOrNull { maxOf(it.esperaA, it.esperaB) } ?: 0).coerceAtLeast(10)
        val faixa = width / 24f
        val barra = faixa * 0.36f
        canvas.drawLine(0f, bottom, width.toFloat(), bottom, grade)
        for (h in historico.porHora) {
            val x = h.hora * faixa + faixa * 0.12f
            val alturaA = (bottom - top) * h.esperaA / max
            val alturaB = (bottom - top) * h.esperaB / max
            canvas.drawRect(x, bottom - alturaA, x + barra, bottom, if (h.hora == historico.melhorA) melhor else barraA)
            canvas.drawRect(x + barra, bottom - alturaB, x + barra * 2, bottom, if (h.hora == historico.melhorB) melhor else barraB)
        }
        for (hora in 0..23 step 3) canvas.drawText("${hora}h", hora * faixa + faixa / 2, height - context.dp(4f), rotulo)
    }
    private fun Context.dp(valor: Float) = valor * resources.displayMetrics.density
}
