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

// Componentes visuais usados em todas as telas: cores, textos, botões e o painel que sobe de baixo.
// Converte dp (unidade que não depende da densidade da tela) em pixels.
fun Context.dp(valor: Int) = (valor * resources.displayMetrics.density).toInt()
// Cores do aplicativo.
object Paleta {
    val marinho = Color.parseColor("#0B2E4F")
    val tinta = Color.parseColor("#0F172A")
    val destaque = Color.parseColor("#0B7A8A")
    val apagado = Color.parseColor("#64748B")
    val fundo = Color.parseColor("#F5F7FA")
    val suave = Color.parseColor("#EEF2F6")
    val linha = Color.parseColor("#E2E8F0")
    val alerta = Color.parseColor("#B45309")
    val perigo = Color.parseColor("#DC2626")
}
// Fábrica de componentes de tela com o estilo do app.
class Ui(val context: Context) {
    fun arredondado(color: Int, raio: Int = 16, borda: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = context.dp(raio).toFloat()
        if (borda != null) setStroke(context.dp(1), borda)
    }
    fun text(valor: CharSequence, size: Float = 15f, color: Int = Paleta.tinta, negrito: Boolean = false) = TextView(context).apply {
        text = valor; textSize = size; setTextColor(color)
        if (negrito) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
    }
    fun coluna() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun linha() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun botao(rotulo: String, principal: Boolean = false, aoTocar: () -> Unit) = TextView(context).apply {
        text = rotulo; textSize = 15f; gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (principal) Color.WHITE else Paleta.tinta)
        background = arredondado(if (principal) Paleta.destaque else Paleta.suave, 14)
        minHeight = context.dp(48); setPadding(context.dp(16), 0, context.dp(16), 0)
        isClickable = true; isFocusable = true; contentDescription = rotulo
        setOnClickListener { aoTocar() }
    }
    fun etiqueta(rotulo: String, selecionada: Boolean, aoTocar: () -> Unit) = text(rotulo, 14f, if (selecionada) Color.WHITE else Paleta.tinta, selecionada).apply {
        gravity = Gravity.CENTER; minHeight = context.dp(40)
        setPadding(context.dp(16), 0, context.dp(16), 0)
        background = arredondado(if (selecionada) Paleta.tinta else Paleta.suave, 20)
        isClickable = true; isFocusable = true; contentDescription = rotulo
        setOnClickListener { aoTocar() }
    }
    fun espaco(height: Int) = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, context.dp(height)) }
    fun entrada(dica: String, valor: String = "") = EditText(context).apply {
        hint = dica; setText(valor); textSize = 15f; setTextColor(Paleta.tinta)
        setHintTextColor(Paleta.apagado); background = arredondado(Paleta.suave, 12)
        setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
    }
    fun rotulo(valor: String) = text(valor, 13f, Paleta.apagado, true).apply { setPadding(0, context.dp(16), 0, context.dp(8)) }
    // "15 min" com o número grande e a unidade pequena.
    fun minutos(valor: Int?, color: Int, size: Int = 32): CharSequence {
        if (valor == null) return "—"
        val numero = valor.toString()
        return SpannableStringBuilder("$numero min").apply {
            setSpan(AbsoluteSizeSpan(size, true), 0, numero.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(AbsoluteSizeSpan(size / 2, true), numero.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    fun grande(valor: String, color: Int, size: Int): CharSequence = SpannableStringBuilder(valor).apply {
        setSpan(AbsoluteSizeSpan(size, true), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    // Painel que sobe da parte de baixo da tela, no lugar das caixas de diálogo.
    fun painel(titulo: String, conteudo: View): Dialog {
        val dialog = Dialog(context)
        val caixa = coluna().apply {
            setPadding(context.dp(24), context.dp(10), context.dp(24), context.dp(24))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                val r = context.dp(24).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        caixa.addView(View(context).apply { background = arredondado(Paleta.linha, 2) },
            LinearLayout.LayoutParams(context.dp(40), context.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = context.dp(18) })
        caixa.addView(text(titulo, 20f, Paleta.tinta, true))
        caixa.addView(espaco(4))
        caixa.addView(conteudo)
        dialog.setContentView(ScrollView(context).apply { addView(caixa) })
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
class Escolha(private val ui: Ui, rotulos: List<String>, inicial: Int = 0, private val aoMudar: (Int) -> Unit = {}) {
    private val linha = ui.linha()
    val view = HorizontalScrollView(ui.context).apply { isHorizontalScrollBarEnabled = false; addView(linha) }
    private var rotulos = rotulos
    var selecionada = inicial
        private set
    init { atualizarTela() }
    fun trocarRotulos(values: List<String>) { rotulos = values; atualizarTela() }
    private fun atualizarTela() {
        linha.removeAllViews()
        rotulos.forEachIndexed { indice, rotulo ->
            linha.addView(ui.etiqueta(rotulo, indice == selecionada) { selecionada = indice; atualizarTela(); aoMudar(indice) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ui.context.dp(40)).apply { marginEnd = ui.context.dp(8) })
        }
    }
}
