package br.com.viaagora

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

// Barras com a espera média por hora do dia, uma cor para cada lado. A melhor hora (entre 6h e 22h) de cada lado fica em verde.
class HistoryChart(context: Context, private val history: History) : View(context) {
    private val barA = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.navy }
    private val barB = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.accent }
    private val best = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(WaitLevel.LOW.color) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.muted; textSize = context.dp(10).toFloat(); textAlign = Paint.Align.CENTER }
    private val grid = Paint().apply { color = Palette.line; strokeWidth = context.dp(1).toFloat() }
    init {
        contentDescription = "Espera média por hora. " + history.hourly.joinToString("; ") { "${it.hour}h: ${it.waitA} e ${it.waitB} minutos" }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(170))
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bottom = height - context.dp(18f)
        val top = context.dp(8f)
        val max = (history.hourly.maxOfOrNull { maxOf(it.waitA, it.waitB) } ?: 0).coerceAtLeast(10)
        val slot = width / 24f
        val bar = slot * 0.36f
        canvas.drawLine(0f, bottom, width.toFloat(), bottom, grid)
        for (h in history.hourly) {
            val x = h.hour * slot + slot * 0.12f
            val heightA = (bottom - top) * h.waitA / max
            val heightB = (bottom - top) * h.waitB / max
            canvas.drawRect(x, bottom - heightA, x + bar, bottom, if (h.hour == history.bestA) best else barA)
            canvas.drawRect(x + bar, bottom - heightB, x + bar * 2, bottom, if (h.hour == history.bestB) best else barB)
        }
        for (hour in 0..23 step 3) canvas.drawText("${hour}h", hour * slot + slot / 2, height - context.dp(4f), label)
    }
    private fun Context.dp(value: Float) = value * resources.displayMetrics.density
}
