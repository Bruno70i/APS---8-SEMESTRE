package br.com.viaagora

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*

fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
object Palette {
    val navy = Color.parseColor("#0B2E4F")
    val ink = Color.parseColor("#0F172A")
    val accent = Color.parseColor("#0B7A8A")
    val muted = Color.parseColor("#64748B")
    val page = Color.parseColor("#F5F7FA")
    val soft = Color.parseColor("#EEF2F6")
    val line = Color.parseColor("#E2E8F0")
    val warning = Color.parseColor("#B45309")
    val danger = Color.parseColor("#DC2626")
}
class Ui(val context: Context) {
    fun rounded(color: Int, radius: Int = 16, border: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = context.dp(radius).toFloat()
        if (border != null) setStroke(context.dp(1), border)
    }
    fun text(value: CharSequence, size: Float = 15f, color: Int = Palette.ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
    }
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = TextView(context).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) Color.WHITE else Palette.ink)
        background = rounded(if (primary) Palette.accent else Palette.soft, 14)
        minHeight = context.dp(48); setPadding(context.dp(16), 0, context.dp(16), 0)
        isClickable = true; isFocusable = true; contentDescription = label
        setOnClickListener { onClick() }
    }
    fun chip(label: String, selected: Boolean, onClick: () -> Unit) = text(label, 14f, if (selected) Color.WHITE else Palette.ink, selected).apply {
        gravity = Gravity.CENTER; minHeight = context.dp(40)
        setPadding(context.dp(16), 0, context.dp(16), 0)
        background = rounded(if (selected) Palette.ink else Palette.soft, 20)
        isClickable = true; isFocusable = true; contentDescription = label
        setOnClickListener { onClick() }
    }
    fun space(height: Int) = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, context.dp(height)) }
    fun input(hintValue: String, value: String = "") = EditText(context).apply {
        hint = hintValue; setText(value); textSize = 15f; setTextColor(Palette.ink)
        setHintTextColor(Palette.muted); background = rounded(Palette.soft, 12)
        setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
    }
    fun label(value: String) = text(value, 13f, Palette.muted, true).apply { setPadding(0, context.dp(16), 0, context.dp(8)) }
    // "15 min" com o número grande e a unidade pequena.
    fun minutes(value: Int?, color: Int, size: Int = 32): CharSequence {
        if (value == null) return "—"
        val number = value.toString()
        return SpannableStringBuilder("$number min").apply {
            setSpan(AbsoluteSizeSpan(size, true), 0, number.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(AbsoluteSizeSpan(size / 2, true), number.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    fun big(value: String, color: Int, size: Int): CharSequence = SpannableStringBuilder(value).apply {
        setSpan(AbsoluteSizeSpan(size, true), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    // Painel que sobe da parte de baixo da tela, no lugar das caixas de diálogo.
    fun sheet(title: String, content: View): Dialog {
        val dialog = Dialog(context)
        val box = column().apply {
            setPadding(context.dp(24), context.dp(10), context.dp(24), context.dp(24))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                val r = context.dp(24).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        box.addView(View(context).apply { background = rounded(Palette.line, 2) },
            LinearLayout.LayoutParams(context.dp(40), context.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = context.dp(18) })
        box.addView(text(title, 20f, Palette.ink, true))
        box.addView(space(4))
        box.addView(content)
        dialog.setContentView(ScrollView(context).apply { addView(box) })
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setWindowAnimations(android.R.style.Animation_InputMethod)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        return dialog
    }
}

// Linha de opções em que só uma fica selecionada.
class Choice(private val ui: Ui, labels: List<String>, initial: Int = 0, private val onChange: (Int) -> Unit = {}) {
    private val row = ui.row()
    val view = HorizontalScrollView(ui.context).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    private var labels = labels
    var selected = initial
        private set
    init { render() }
    fun relabel(values: List<String>) { labels = values; render() }
    private fun render() {
        row.removeAllViews()
        labels.forEachIndexed { index, label ->
            row.addView(ui.chip(label, index == selected) { selected = index; render(); onChange(index) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ui.context.dp(40)).apply { marginEnd = ui.context.dp(8) })
        }
    }
}
