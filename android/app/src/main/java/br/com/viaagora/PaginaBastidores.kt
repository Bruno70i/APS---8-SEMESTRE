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
class PaginaBastidores(private val context: Context, private val ui: Ui) {
    // Quem fornece o estado do servidor (a rota /v1/system).
    var fonte: (((EstadoSistema) -> Unit, (String) -> Unit) -> Unit)? = null
    var minhaReplica: String? = null
    // Endereço do servidor conectado: a VPS oficial ou o computador de quem roda o sistema localmente.
    var servidor: String = SERVIDOR_OFICIAL
        set(valor) { field = valor; diagrama.rotuloServidor = if (valor == SERVIDOR_OFICIAL) "VPS · SEDDA.COM.BR" else "SERVIDOR · ${valor.substringAfter("://").uppercase()}" }
    private val agendador = Handler(Looper.getMainLooper())
    private val conteudo = ui.coluna().apply { setPadding(context.dp(16), context.dp(4), context.dp(16), context.dp(96)) }
    val view = ScrollView(context).apply { addView(conteudo) }
    private val introducao = ui.text("", 14f, Paleta.apagado).apply { setLineSpacing(0f, 1.15f) }
    private val diagrama = DiagramaRede(context, ::explicarProcesso)
    private val etiquetaPasso = ui.text("", 11f, Paleta.destaque, true).apply { letterSpacing = 0.08f }
    private val legenda = ui.text("", 15f, Paleta.tinta).apply { setLineSpacing(0f, 1.2f); minLines = 3 }
    private val etiquetas = linkedMapOf<String, TextView>()
    private val acoes = ui.linha()
    private val tituloLinhaTempo = ui.text("", 13f, Paleta.apagado)
    private val linhaTempo = ui.coluna()
    private val metricas = ui.linha()
    private var estado: EstadoSistema? = null
    private var ultimoEvento: RastroEvento? = null
    private var ultimoAnimado: String? = null
    private var animando = false
    private var aula: List<PassoRastro>? = null
    private var passoDaAula = 0
    private var visivel = false
    private val consulta = object : Runnable { override fun run() { recarregar(); agendador.postDelayed(this, 5_000) } }

    init {
        conteudo.addView(ui.text("SISTEMA DISTRIBUÍDO", 11f, Paleta.destaque, true).apply { letterSpacing = 0.1f; setPadding(0, context.dp(8), 0, context.dp(6)) })
        conteudo.addView(ui.text("Por dentro do ViaAgora", 24f, Paleta.tinta, true))
        conteudo.addView(ui.espaco(6))
        conteudo.addView(introducao)
        conteudo.addView(ui.espaco(16))
        val quadroDiagrama = ui.coluna().apply { setPadding(context.dp(10), context.dp(12), context.dp(10), context.dp(14)); background = ui.arredondado(Color.WHITE, 22) }
        quadroDiagrama.addView(diagrama)
        quadroDiagrama.addView(ui.text(textoLegenda(), 12f, Paleta.apagado).apply { gravity = Gravity.CENTER; setPadding(0, context.dp(10), 0, 0) })
        conteudo.addView(quadroDiagrama)
        conteudo.addView(ui.espaco(12))
        val explicacao = ui.coluna().apply { setPadding(context.dp(18), context.dp(14), context.dp(18), context.dp(14)); background = ui.arredondado(Paleta.suave, 18) }
        explicacao.addView(etiquetaPasso); explicacao.addView(ui.espaco(6)); explicacao.addView(legenda)
        conteudo.addView(explicacao)
        conteudo.addView(ui.espaco(12))
        val linhaEtiquetas = ui.linha()
        explicacoesConceitos.keys.forEach { conceito ->
            val etiqueta = ui.etiqueta(conceito, false) { explicarConceito(conceito) }
            etiquetas[conceito] = etiqueta
            linhaEtiquetas.addView(etiqueta, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, context.dp(36)).apply { marginEnd = context.dp(6) })
        }
        conteudo.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(linhaEtiquetas) })
        conteudo.addView(ui.espaco(14))
        conteudo.addView(acoes)
        conteudo.addView(secao("O caminho de um evento"))
        conteudo.addView(tituloLinhaTempo)
        conteudo.addView(ui.espaco(8))
        conteudo.addView(linhaTempo)
        conteudo.addView(secao("Agora no servidor"))
        conteudo.addView(metricas)
        legendaInicial()
        mostrarAcoes()
    }

    fun show() { if (visivel) return; visivel = true; agendador.post(consulta) }
    fun hide() { visivel = false; agendador.removeCallbacks(consulta) }
    fun reiniciar() { estado = null; ultimoEvento = null; ultimoAnimado = null; minhaReplica = null; aula = null; diagrama.stop(); animando = false; atualizarTela(); if (visivel) agendador.postDelayed({ recarregar() }, 300) }

    // Evento ao vivo do WebSocket: a réplica se apresenta no "ready"; nos demais, buscamos o rastro completo.
    fun aoEventoAoVivo(tipo: String, replica: String?, temEventoId: Boolean) {
        if (tipo == "ready" && replica != null) { minhaReplica = replica; atualizarTela() }
        if (temEventoId && visivel) agendador.postDelayed({ recarregar() }, 1200)
    }

    private fun recarregar() {
        val carregar = fonte
        if (carregar == null) return
        carregar({ s -> estado = s; s.eventos.firstOrNull()?.let { ultimoEvento = it }; atualizarTela(); animarAutomatico() }, { if (visivel) { estado = null; atualizarTela() } })
    }
    private fun animarAutomatico() {
        val evento = ultimoEvento ?: return
        // A chave muda quando o mesmo evento avança (ex.: estava preso na outbox e o broker voltou), e então reanimamos.
        val key = "${evento.eventoId}:${evento.publicadoEm != null}:${evento.entregas.size}"
        if (!visivel || aula != null || animando || key == ultimoAnimado) return
        val retomado = ultimoAnimado?.startsWith("${evento.eventoId}:false") == true
        ultimoAnimado = key
        val passos = passosDoRastro(evento)
        if (retomado) {
            val start = passos.indexOfFirst { it.saltos == listOf("wk" to "mq") }.coerceAtLeast(0)
            etiquetaPasso.text = "RECUPERAÇÃO"; legenda.text = "O broker voltou: o worker publica o evento que estava guardado na outbox."
            agendador.postDelayed({ animarTudo(passos, corDoEvento(evento), start) }, 1500)
        } else animarTudo(passos, corDoEvento(evento))
    }
    private fun corDoEvento(evento: RastroEvento) = Color.parseColor(if (evento.tipo.startsWith("report")) "#D85A30" else "#378ADD")
    private fun animarTudo(passos: List<PassoRastro>, color: Int, indice: Int = 0) {
        if (indice >= passos.size || !visivel) { animando = false; return }
        animando = true
        val passo = passos[indice]
        mostrarPasso(passo, "Passo ${indice + 1} de ${passos.size}")
        diagrama.animar(passo.saltos, color) { agendador.postDelayed({ animarTudo(passos, color, indice + 1) }, 450) }
    }
    private fun mostrarPasso(passo: PassoRastro, posicao: String) {
        etiquetaPasso.text = posicao.uppercase(); legenda.text = passo.legenda
        etiquetas.forEach { (conceito, etiqueta) ->
            val ativo = conceito in passo.conceitos
            etiqueta.background = ui.arredondado(if (ativo) Paleta.destaque else Paleta.suave, 20); etiqueta.setTextColor(if (ativo) Color.WHITE else Paleta.tinta)
        }
    }

    private fun atualizarTela() {
        val s = estado
        introducao.text = when {
            s == null -> "Conectando ao servidor…"
            else -> "Estes processos trabalham juntos no servidor. Seu celular está ligado à ${minhaReplica?.let { "réplica $it" } ?: "uma das réplicas"}, destacada no desenho."
        }
        diagrama.saude = saude(s)
        diagrama.voce = minhaReplica?.let { chaveReplica(it) }
        diagrama.rotulosCelulares = listOf("api1", "api2").associate { key ->
            val celulares = s?.processos?.firstOrNull { it.papel == "api" && chaveReplica(it.processo) == key }?.celulares
            val meu = minhaReplica != null && chaveReplica(minhaReplica) == key
            chaveCelulares(key) to when {
                celulares == null -> if (meu) "Você" else "Celulares"
                meu -> if (celulares > 1) "Você +${celulares - 1}" else "Você"
                else -> "$celulares celular${if (celulares == 1) "" else "es"}"
            }
        }
        mostrarLinhaTempo()
        if (aula == null) mostrarAcoes()
        metricas.removeAllViews()
        listOf(Triple("bolt", "eventos na última hora", s?.ultimaHora), Triple("queue", "esperando na outbox", s?.pendentes), Triple("repeat", "reenvios ignorados", s?.reenvios))
            .forEachIndexed { i, (nome, rotulo, valor) ->
                val caixa = ui.coluna().apply { setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12)); background = ui.arredondado(Color.WHITE, 16); isClickable = true }
                caixa.addView(icone(nome, 22, Paleta.destaque))
                caixa.addView(ui.text(valor?.toString() ?: "—", 22f, Paleta.tinta, true).apply { setPadding(0, context.dp(10), 0, 0) })
                caixa.addView(ui.text(rotulo, 11f, Paleta.apagado).apply { setPadding(0, context.dp(4), 0, 0) })
                caixa.setOnClickListener { explicarConceito(listOf("Pub/Sub", "Outbox", "Idempotência")[i]) }
                metricas.addView(caixa, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = context.dp(8) })
            }
    }
    private fun saude(s: EstadoSistema?): Map<String, DiagramaRede.Saude> {
        if (s == null) return emptyMap()
        fun saudeDe(vivo: Boolean?) = when (vivo) { true -> DiagramaRede.Saude.NO_AR; false -> DiagramaRede.Saude.FORA_DO_AR; null -> DiagramaRede.Saude.DESCONHECIDA }
        val apisVivas = s.processos.filter { it.papel == "api" }
        val coletor = s.processos.firstOrNull { it.papel == "collector" }
        return mapOf(
            "api1" to saudeDe(apisVivas.firstOrNull { chaveReplica(it.processo) == "api1" }?.vivo), "api2" to saudeDe(apisVivas.firstOrNull { chaveReplica(it.processo) == "api2" }?.vivo),
            "wk" to saudeDe(s.processos.firstOrNull { it.papel == "worker" }?.vivo), "col" to saudeDe(coletor?.vivo),
            "db" to DiagramaRede.Saude.NO_AR, "nginx" to DiagramaRede.Saude.NO_AR, "mq" to saudeDe(s.brokerNoAr), "semil" to saudeDe(coletor?.coletorOk),
            "cel1" to saudeDe(apisVivas.firstOrNull { chaveReplica(it.processo) == "api1" }?.celulares?.let { it > 0 }?.takeIf { it }),
            "cel2" to saudeDe(apisVivas.firstOrNull { chaveReplica(it.processo) == "api2" }?.celulares?.let { it > 0 }?.takeIf { it }),
        )
    }
    private fun mostrarLinhaTempo() {
        linhaTempo.removeAllViews()
        val evento = ultimoEvento
        if (evento == null) { tituloLinhaTempo.text = "Nenhum evento ainda. Publique um relato e volte aqui."; return }
        tituloLinhaTempo.text = if (evento.eventoId == "exemplo") "Exemplo · relato publicado" else "${tituloEvento(evento.tipo)} · ${haQuanto(evento.criadoEm)}"
        val caixa = ui.coluna().apply { setPadding(context.dp(16), context.dp(10), context.dp(16), context.dp(10)); background = ui.arredondado(Color.WHITE, 18) }
        passosDoRastro(evento).forEach { passo ->
            val linha = ui.linha().apply { setPadding(0, context.dp(7), 0, context.dp(7)) }
            val nome = passo.saltos.firstOrNull()?.second?.let { DiagramaRede.iconeDe(it) } ?: if (passo.rotulo.startsWith("Entregue")) "repeat" else "clock"
            linha.addView(icone(nome, 18, Paleta.destaque).apply { background = ui.arredondado(Paleta.suave, 10); setPadding(context.dp(7), context.dp(7), context.dp(7), context.dp(7)) },
                LinearLayout.LayoutParams(context.dp(32), context.dp(32)).apply { marginEnd = context.dp(12) })
            linha.addView(ui.text(passo.rotulo, 14f, Paleta.tinta), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            linha.addView(ui.text(passo.ms?.let { "+$it ms" } ?: "", 13f, Paleta.apagado))
            caixa.addView(linha)
        }
        tempoTotal(evento)?.takeIf { evento.entregas.isNotEmpty() }?.let { total ->
            caixa.addView(View(context).apply { setBackgroundColor(Paleta.linha) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = context.dp(6) })
            val linha = ui.linha().apply { setPadding(0, context.dp(12), 0, context.dp(4)) }
            linha.addView(ui.text("Do banco até os celulares", 14f, Paleta.tinta, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            linha.addView(ui.text("$total ms", 15f, Paleta.destaque, true))
            caixa.addView(linha)
        }
        linhaTempo.addView(caixa)
    }
    private fun mostrarAcoes() {
        acoes.removeAllViews()
        val passos = aula
        val botoes = if (passos == null) listOf(
            "▶  Assistir" to { repetir() },
            "Passo a passo" to { iniciarAula() },
        ) else listOf(
            "Voltar" to { irParaPasso(passoDaAula - 1) },
            (if (passoDaAula >= passos.size - 1) "Concluir" else "Próximo") to { if (passoDaAula >= passos.size - 1) encerrarAula() else irParaPasso(passoDaAula + 1) },
        )
        botoes.forEachIndexed { i, (rotulo, action) ->
            acoes.addView(ui.botao(rotulo, if (passos == null) i == 0 else i == 1, action), LinearLayout.LayoutParams(0, context.dp(46), 1f).apply { if (i > 0) marginStart = context.dp(8) })
        }
    }
    private fun repetir() {
        if (animando) return
        val evento = ultimoEvento ?: rastroExemplo().also { ultimoEvento = it; atualizarTela() }
        animarTudo(passosDoRastro(evento), corDoEvento(evento))
    }
    // Modo aula: o pacote anda um salto por vez, no ritmo de quem está explicando.
    private fun iniciarAula() {
        val evento = ultimoEvento ?: rastroExemplo().also { ultimoEvento = it; atualizarTela() }
        diagrama.stop(); animando = false
        aula = passosDoRastro(evento); irParaPasso(0)
    }
    private fun irParaPasso(indice: Int) {
        val passos = aula ?: return
        passoDaAula = indice.coerceIn(0, passos.size - 1)
        val passo = passos[passoDaAula]
        mostrarPasso(passo, "Passo ${passoDaAula + 1} de ${passos.size}")
        ultimoEvento?.let { diagrama.animar(passo.saltos, corDoEvento(it)) {} }
        mostrarAcoes()
    }
    private fun encerrarAula() { aula = null; legendaInicial(); mostrarAcoes() }
    private fun legendaInicial() {
        etiquetaPasso.text = "COMO LER O DESENHO"
        legenda.text = "Cada quadro é um processo. As bolinhas que andam são mensagens. Toque num quadro para saber o que ele faz."
    }
    private fun secao(titulo: String) = ui.text(titulo, 17f, Paleta.tinta, true).apply { setPadding(0, context.dp(28), 0, context.dp(6)) }
    private fun icone(nome: String, size: Int, color: Int) = ImageView(context).apply {
        setImageDrawable(DesenhoIcone(nome, color)); layoutParams = LinearLayout.LayoutParams(context.dp(size), context.dp(size))
    }
    private fun textoLegenda(): CharSequence = SpannableStringBuilder().apply {
        listOf(Color.parseColor("#16A34A") to "no ar", Paleta.perigo to "fora do ar", Color.parseColor("#D85A30") to "relato", Color.parseColor("#378ADD") to "leitura do painel")
            .forEachIndexed { i, (color, rotulo) ->
                if (i > 0) append("    ")
                val start = length; append("●"); setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); append(" $rotulo")
            }
    }

    private fun explicarProcesso(key: String) {
        val info = explicacoesProcessos[key] ?: return
        val corpo = ui.coluna()
        situacaoAoVivo(key)?.let { (text, color) -> corpo.addView(ui.text(text, 14f, color, true).apply { setPadding(0, context.dp(4), 0, context.dp(10)) }) }
        corpo.addView(ui.text(info.oQue, 15f, Paleta.tinta).apply { setLineSpacing(0f, 1.2f) })
        info.seCair?.let { corpo.addView(ui.text("Se cair: $it", 14f, Paleta.apagado).apply { setPadding(0, context.dp(12), 0, 0); setLineSpacing(0f, 1.2f) }) }
        corpo.addView(ui.espaco(14))
        corpo.addView(ui.text("Conceito: ${info.conceito}", 13f, Paleta.destaque, true))
        corpo.addView(ui.text(explicacoesConceitos[info.conceito].orEmpty(), 13f, Paleta.apagado).apply { setPadding(0, context.dp(4), 0, context.dp(8)) })
        ui.painel(info.titulo, corpo).show()
    }
    private fun situacaoAoVivo(key: String): Pair<String, Int>? {
        val s = estado ?: return null
        val processo = when (key) {
            "api1", "api2" -> s.processos.firstOrNull { it.papel == "api" && chaveReplica(it.processo) == key }
            "wk" -> s.processos.firstOrNull { it.papel == "worker" }
            "col" -> s.processos.firstOrNull { it.papel == "collector" }
            else -> null
        }
        return when {
            key == "mq" -> if (s.brokerNoAr) "● Conectado às réplicas" to Paleta.destaque else "● Fora do ar · ${s.pendentes} evento(s) esperando na outbox" to Paleta.perigo
            key == "db" -> "● Respondendo" to Paleta.destaque
            key == "semil" -> when (s.processos.firstOrNull { it.papel == "collector" }?.coletorOk) { false -> "● Última leitura falhou" to Paleta.alerta; true -> "● Última leitura OK" to Paleta.destaque; null -> null }
            processo == null -> null
            processo.vivo -> "● Vivo · último sinal há ${processo.idadeMs / 1000} s" + (processo.celulares?.let { " · $it celular(es)" } ?: "") to Paleta.destaque
            else -> "● Sem sinal há ${processo.idadeMs / 1000} s" to Paleta.perigo
        }
    }
    private fun explicarConceito(conceito: String) {
        val corpo = ui.coluna()
        corpo.addView(ui.text(explicacoesConceitos[conceito].orEmpty(), 15f, Paleta.tinta).apply { setLineSpacing(0f, 1.2f); setPadding(0, context.dp(4), 0, context.dp(8)) })
        if (conceito == "Idempotência") estado?.let { corpo.addView(ui.text("Reenvios ignorados até agora: ${it.reenvios}", 13f, Paleta.apagado)) }
        ui.painel(conceito, corpo).show()
    }
    private fun haQuanto(valor: String) = runCatching {
        val seconds = Duration.between(Instant.parse(valor), Instant.now()).seconds.coerceAtLeast(0)
        if (seconds < 60) "há $seconds s" else if (seconds < 3600) "há ${seconds / 60} min" else "há ${seconds / 3600} h"
    }.getOrDefault("")
}
