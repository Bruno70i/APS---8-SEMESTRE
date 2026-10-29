package br.com.viaagora

import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

// Ícones de traço (grade 24 x 24), os mesmos desenhos do site.
object Icons {
    private val data = mapOf(
        "phone" to "M8.5,2h7a2.5,2.5 0,0 1,2.5,2.5v15a2.5,2.5 0,0 1,-2.5,2.5h-7a2.5,2.5 0,0 1,-2.5,-2.5v-15a2.5,2.5 0,0 1,2.5,-2.5z M10.5,5h3 M11,18.5h2",
        "server" to "M4.5,4h15a1.5,1.5 0,0 1,1.5,1.5v4a1.5,1.5 0,0 1,-1.5,1.5h-15a1.5,1.5 0,0 1,-1.5,-1.5v-4a1.5,1.5 0,0 1,1.5,-1.5z " +
            "M4.5,13h15a1.5,1.5 0,0 1,1.5,1.5v4a1.5,1.5 0,0 1,-1.5,1.5h-15a1.5,1.5 0,0 1,-1.5,-1.5v-4a1.5,1.5 0,0 1,1.5,-1.5z M7,7.5h0.01 M7,16.5h0.01 M11,7.5h6 M11,16.5h6",
        "servers" to "M4.5,3h15a1.5,1.5 0,0 1,1.5,1.5v2.5a1.5,1.5 0,0 1,-1.5,1.5h-15a1.5,1.5 0,0 1,-1.5,-1.5v-2.5a1.5,1.5 0,0 1,1.5,-1.5z " +
            "M4.5,9.25h15a1.5,1.5 0,0 1,1.5,1.5v2.5a1.5,1.5 0,0 1,-1.5,1.5h-15a1.5,1.5 0,0 1,-1.5,-1.5v-2.5a1.5,1.5 0,0 1,1.5,-1.5z " +
            "M4.5,15.5h15a1.5,1.5 0,0 1,1.5,1.5v2.5a1.5,1.5 0,0 1,-1.5,1.5h-15a1.5,1.5 0,0 1,-1.5,-1.5v-2.5a1.5,1.5 0,0 1,1.5,-1.5z " +
            "M6.5,5.75h0.01 M6.5,12h0.01 M6.5,18.25h0.01 M11,5.75h6 M11,12h6 M11,18.25h6",
        "balancer" to "M7,12a2,2 0 1,1 -4,0a2,2 0 1,1 4,0z M7,12h3l4,-5.5h4 M10,12l4,5.5h4 M16.5,4.5l2,2 -2,2 M16.5,15.5l2,2 -2,2",
        "database" to "M4.5,5.5a7.5,2.8 0 1,0 15,0a7.5,2.8 0 1,0 -15,0z M4.5,5.5v13c0,1.55 3.36,2.8 7.5,2.8s7.5,-1.25 7.5,-2.8v-13 M4.5,12c0,1.55 3.36,2.8 7.5,2.8s7.5,-1.25 7.5,-2.8",
        "gear" to "M19.11,9.32L21.8,10.01L21.8,13.99L19.11,14.68L18.92,15.14L20.33,17.53L17.53,20.33L15.14,18.92L14.68,19.11L13.99,21.8L10.01,21.8L9.32,19.11" +
            "L8.86,18.92L6.47,20.33L3.67,17.53L5.08,15.14L4.89,14.68L2.2,13.99L2.2,10.01L4.89,9.32L5.08,8.86L3.67,6.47L6.47,3.67L8.86,5.08L9.32,4.89L10.01,2.2" +
            "L13.99,2.2L14.68,4.89L15.14,5.08L17.53,3.67L20.33,6.47L18.92,8.86z M15,12a3,3 0 1,1 -6,0a3,3 0 1,1 6,0z",
        "sliders" to "M4,7h10 M18,7h2 M18,7a2,2 0 1,1 -4,0a2,2 0 1,1 4,0z M4,17h2 M10,17h10 M10,17a2,2 0 1,1 -4,0a2,2 0 1,1 4,0z",
        "queue" to "M5,6h14a2,2 0 0,1 2,2v8a2,2 0 0,1 -2,2h-14a2,2 0 0,1 -2,-2v-8a2,2 0 0,1 2,-2z M3.5,7.5l8.5,6 8.5,-6 M6,3h12",
        "download" to "M12,3v11.5 M7.5,10l4.5,4.5 4.5,-4.5 M4,19.5h16",
        "globe" to "M21,12a9,9 0 1,1 -18,0a9,9 0 1,1 18,0z M16,12a4,9 0 1,1 -8,0a4,9 0 1,1 8,0z M3,12h18",
        "bolt" to "M13,2.5L4.5,13.5H11l-1,8 8.5,-11H12z",
        "clock" to "M21,12a9,9 0 1,1 -18,0a9,9 0 1,1 18,0z M12,7v5l3,2",
        "repeat" to "M17,2.5l3,3 -3,3 M4,11.5v-1a5,5 0 0,1 5,-5h11 M7,21.5l-3,-3 3,-3 M20,12.5v1a5,5 0 0,1 -5,5H4",
    )
    private val cache = mutableMapOf<String, Path>()
    fun path(name: String): Path = cache.getOrPut(name) { PathParser.createPathFromPathData(data.getValue(name)) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    // Desenha o ícone com o centro em (cx, cy) e o lado "size", nas unidades atuais do canvas.
    fun draw(canvas: Canvas, name: String, cx: Float, cy: Float, size: Float, color: Int) {
        val k = size / 24f
        paint.color = color; paint.strokeWidth = 1.7f
        canvas.save(); canvas.translate(cx - size / 2, cy - size / 2); canvas.scale(k, k)
        canvas.drawPath(path(name), paint)
        canvas.restore()
    }
}

// O mesmo ícone como Drawable, para usar ao lado de textos.
class IconDrawable(private val name: String, private val color: Int) : Drawable() {
    override fun draw(canvas: Canvas) {
        val b = bounds
        Icons.draw(canvas, name, b.exactCenterX(), b.exactCenterY(), minOf(b.width(), b.height()).toFloat(), color)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
