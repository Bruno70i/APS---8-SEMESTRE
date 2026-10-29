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

// Tela principal: abas Travessias, Mapa, Relatos e Bastidores. Recebe os dados do servidor e os avisos ao vivo
// pelo WebSocket; por garantia, busca tudo de novo a cada 30 segundos.
class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("viaagora_private", MODE_PRIVATE) }
    private val estado = EstadoApp()
    private lateinit var ui: Ui
    private lateinit var painelMapa: PainelMapa
    private lateinit var textoStatus: TextView
    private lateinit var abas: List<TextView>
    private lateinit var listaTravessias: LinearLayout
    private lateinit var listaRelatos: LinearLayout
    private lateinit var paginas: List<View>
    private lateinit var bastidores: PaginaBastidores
    private lateinit var botaoNovoRelato: View
    private var remoto: RepositorioRemoto? = null
    private var dados = Panorama(catalogoTravessias, emptyList(), InfoFonte(null, false, null, null))
    private var aba = 0
    private var filtroTravessia: String? = null
    private var aoVivo = false
    private var erroConexao = false
    private var localizacaoPendente: (() -> Unit)? = null
    private var ouvinteLocalizacao: LocationListener? = null
    private val agendador = Handler(Looper.getMainLooper())
    // Busca de novo a cada 30 s: se o canal ao vivo cair (app em segundo plano, troca de rede), os relatos ainda chegam.
    private val atualizacaoPeriodica = object : Runnable { override fun run() { recarregar(); agendador.postDelayed(this, 30_000) } }
    private var relatosConhecidos: Set<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.WHITE
        WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true }
        val raiz = FrameLayout(this).apply { setBackgroundColor(Paleta.fundo) }
        ViewCompat.setOnApplyWindowInsetsListener(raiz) { view, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(barras.left, barras.top, barras.right, barras.bottom); insets
        }
        setContentView(raiz)
        val tela = ui.coluna()
        raiz.addView(tela, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val cabecalho = ui.linha().apply { setPadding(dp(20), dp(18), dp(8), dp(12)) }
        val titulos = ui.coluna()
        titulos.addView(ui.text("Balsas agora", 28f, Paleta.tinta, true))
        textoStatus = ui.text("", 13f, Paleta.apagado).apply { setPadding(0, dp(6), 0, 0); setOnClickListener { recarregar() } }
        titulos.addView(textoStatus)
        cabecalho.addView(titulos, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cabecalho.addView(ImageView(this).apply { setImageDrawable(DesenhoIcone("sliders", Paleta.tinta)); setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "Ajustes"; isClickable = true; setOnClickListener { abrirAjustes() } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        tela.addView(cabecalho)

        val conteudo = FrameLayout(this)
        tela.addView(conteudo, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        listaTravessias = ui.coluna().apply { setPadding(dp(16), dp(4), dp(16), dp(96)) }
        listaRelatos = ui.coluna().apply { setPadding(dp(16), dp(4), dp(16), dp(96)) }
        painelMapa = PainelMapa(this, savedInstanceState, ::abrirMarcador, { lat, lng -> abrirNovoRelato(null, lat, lng) }, ::localizar)
        bastidores = PaginaBastidores(this, ui)
        paginas = listOf(ScrollView(this).apply { addView(listaTravessias) }, painelMapa, ScrollView(this).apply { addView(listaRelatos) }, bastidores.view)
        paginas.forEach { conteudo.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)) }

        val navegacao = ui.linha().apply { setBackgroundColor(Color.WHITE) }
        abas = listOf("Travessias", "Mapa", "Relatos", "Bastidores").mapIndexed { indice, rotulo ->
            ui.text(rotulo, 13f, Paleta.apagado, true).apply { gravity = Gravity.CENTER; isClickable = true; contentDescription = rotulo; setOnClickListener { aba = indice; atualizarTela() } }
                .also { navegacao.addView(it, LinearLayout.LayoutParams(0, dp(60), 1f)) }
        }
        tela.addView(View(this).apply { setBackgroundColor(Paleta.linha) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        tela.addView(navegacao)

        botaoNovoRelato = ui.text("+", 30f, Color.WHITE).apply {
            gravity = Gravity.CENTER; background = ui.arredondado(Paleta.destaque, 30); elevation = dp(6).toFloat()
            contentDescription = "Relatar fila ou problema"; isClickable = true
            setOnClickListener { if (aba == 1) { val p = painelMapa.centro(); abrirNovoRelato(null, p.first, p.second) } else abrirNovoRelato(null) }
        }
        raiz.addView(botaoNovoRelato, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(20), dp(80)) })

        // Abre no servidor oficial; quem escolheu outro servidor nos Ajustes continua nele.
        val salvo = prefs.getString("last_server", "").orEmpty().ifBlank { SERVIDOR_OFICIAL }
        runCatching { ativar(salvo) }.onFailure { ativar(SERVIDOR_OFICIAL) }
    }

    private fun ativar(servidor: String) {
        remoto?.fechar(); remoto = null
        estado.selecionar(servidor)
        aoVivo = false; erroConexao = false; relatosConhecidos = null
        bastidores.fonte = { ok, falhou -> remoto?.sistema(ok, falhou) }
        bastidores.servidor = estado.servidor
        bastidores.reiniciar()
        val repositorio = RepositorioRemoto(prefs, estado.servidor); remoto = repositorio
        dados = repositorio.emCache() ?: Panorama(catalogoTravessias, emptyList(), InfoFonte(null, false, null, null)); atualizarTela()
        val senha = estado.geracao
        repositorio.conectar({ if (estado.aceita(senha)) recarregar() }, { connected -> if (estado.aceita(senha)) { aoVivo = connected; atualizarTela() } },
            { tipo, replica, rastreavel -> if (estado.aceita(senha)) bastidores.aoEventoAoVivo(tipo, replica, rastreavel) })
        recarregar()
    }
    private fun recarregar() {
        val senha = estado.geracao
        remoto?.buscar({ panorama -> if (estado.aceita(senha)) { erroConexao = false; atualizar(panorama) } },
            { message -> if (estado.aceita(senha)) { erroConexao = true; atualizarTela(); mostrarAviso(message) } })
    }
    private fun atualizar(panorama: Panorama) {
        dados = panorama
        // Relato novo de outra pessoa: avisa na hora (na primeira carga só memoriza o que já existia).
        val ids = panorama.relatos.map { it.id }.toSet()
        relatosConhecidos?.let { conhecidos ->
            panorama.relatos.firstOrNull { it.id !in conhecidos && it.situacao == "active" && it.autorId != autorId() }
                ?.let { mostrarAviso("Novo relato · ${tituloRelato(it)}") }
        }
        relatosConhecidos = ids
        atualizarTela()
    }

    // Uma única linha de status; vermelho/âmbar só quando há algo errado.
    private fun atualizarStatus() {
        val fonte = dados.fonte
        val (text, color) = when {
            erroConexao -> (if (fonte.ultimoSucessoEm != null) "Sem conexão · dados de ${tempoRelativo(fonte.ultimoSucessoEm)} · toque para tentar"
                else "Sem conexão · toque para tentar de novo") to Paleta.perigo
            fonte.ultimoSucessoEm == null -> "Aguardando o painel oficial" to Paleta.apagado
            !fonte.disponivel -> "Painel oficial fora do ar · último dado ${tempoRelativo(fonte.ultimoSucessoEm)}" to Paleta.alerta
            aoVivo -> "● Ao vivo · atualizado ${tempoRelativo(fonte.ultimoSucessoEm)}" to Paleta.destaque
            else -> "Atualizado ${tempoRelativo(fonte.ultimoSucessoEm)}" to Paleta.apagado
        }
        textoStatus.text = text; textoStatus.setTextColor(color)
    }
    private fun atualizarTela() {
        atualizarStatus()
        abas.forEachIndexed { indice, view -> view.setTextColor(if (indice == aba) Paleta.destaque else Paleta.apagado) }
        paginas.forEachIndexed { indice, view -> view.visibility = if (indice == aba) View.VISIBLE else View.GONE }
        botaoNovoRelato.visibility = if (aba == 3) View.GONE else View.VISIBLE
        if (aba == 3) bastidores.show() else bastidores.hide()
        listaTravessias.removeAllViews()
        dados.travessias.forEach { listaTravessias.addView(cartaoTravessia(it)); listaTravessias.addView(ui.espaco(12)) }
        val ativos = dados.relatos.filter { it.situacao == "active" }
        painelMapa.atualizar(dados.travessias.flatMap { c -> listOf("A", "B").map { lado ->
            val t = c.terminal(lado); MarcadorMapa("terminal:${c.id}:$lado", t.latitude, t.longitude, nivelEspera(espera(c, lado), c.situacao?.operando ?: true).color, true) } } +
            ativos.map { MarcadorMapa("report:${it.id}", it.latitude, it.longitude, infoCategoria(it.categoria).color, false) },
            dados.travessias.map { RotaMapa(it.rota, if (it.parada) "#DC2626" else "#0B7A8A") })
        mostrarRelatos()
    }
    private fun espera(travessia: Travessia, lado: String) = travessia.situacao?.let { if (lado == "A") it.esperaA else it.esperaB }
    private fun corDoNivel(c: Travessia, lado: String) = Color.parseColor(nivelEspera(espera(c, lado), c.situacao?.operando ?: true).color)
    // Com a travessia parada, mostrar "0 min" engana: aparece "Parada" no lugar dos minutos.
    private fun valorDoLado(c: Travessia, lado: String, size: Int = 32): CharSequence =
        if (c.parada) ui.grande("Parada", Paleta.perigo, size * 3 / 4) else ui.minutos(espera(c, lado), corDoNivel(c, lado), size)
    private fun relatosRecentes(c: Travessia) = dados.relatos.filter { it.travessiaId == c.id && it.situacao == "active" }
    private fun textoRelatosParada(c: Travessia) = "⚠ ${c.relatosParada} relato${if (c.relatosParada > 1) "s" else ""} de travessia parada"

    private fun cartaoTravessia(c: Travessia): View {
        val s = c.situacao
        val cartao = ui.coluna().apply {
            setPadding(dp(20), dp(18), dp(20), dp(18)); background = ui.arredondado(Color.WHITE, 20)
            isClickable = true; isFocusable = true; setOnClickListener { detalhesTravessia(c) }
        }
        val titulo = ui.linha()
        titulo.addView(ui.text(c.nome, 17f, Paleta.tinta, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (s != null) titulo.addView(ui.text(if (s.operando) "⛴ ${s.embarcacoes}" else "⛴ 0", 13f, if (s.operando) Paleta.apagado else Paleta.perigo))
        cartao.addView(titulo); cartao.addView(ui.espaco(14))
        val lados = ui.linha().apply { gravity = Gravity.TOP }
        listOf("A", "B").forEachIndexed { indice, lado ->
            if (indice > 0) lados.addView(View(this).apply { setBackgroundColor(Paleta.linha) }, LinearLayout.LayoutParams(dp(1), dp(44)).apply { marginEnd = dp(16) })
            val caixa = ui.coluna()
            caixa.addView(ui.text(c.terminal(lado).nome, 13f, Paleta.apagado).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            caixa.addView(ui.espaco(4))
            caixa.addView(ui.text(valorDoLado(c, lado), 15f, Paleta.tinta, true))
            (if (lado == "A") c.comunidadeA else c.comunidadeB)?.let { caixa.addView(ui.text("~${it.minutosEspera} min pela comunidade", 12f, Paleta.apagado).apply { setPadding(0, dp(4), 0, 0) }) }
            lados.addView(caixa, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        cartao.addView(lados)
        val (message, color) = when {
            s == null -> "Sem leitura do painel ainda" to Paleta.apagado
            c.parada -> (s.aviso?.takeIf { it.isNotBlank() } ?: "Sem embarcação em operação") to Paleta.perigo
            s.atrasado -> "Dado atrasado · ${tempoRelativo(s.coletadoEm)}" to Paleta.alerta
            c.relatosParada > 0 -> textoRelatosParada(c) to Paleta.perigo
            !s.aviso.isNullOrBlank() -> "⚠ ${s.aviso}" to Paleta.alerta
            else -> null to Paleta.apagado
        }
        if (message != null) cartao.addView(ui.text(message, 13f, color).apply { setPadding(0, dp(12), 0, 0); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        relatosRecentes(c).takeIf { it.isNotEmpty() }?.let { recentes ->
            val last = recentes.first()
            cartao.addView(ui.text("💬 ${recentes.size} relato${if (recentes.size > 1) "s" else ""} · ${infoCategoria(last.categoria).rotulo} ${tempoRelativo(last.criadoEm)}",
                13f, Paleta.destaque).apply { setPadding(0, dp(10), 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }
        cartao.contentDescription = if (c.parada) "${c.nome}: travessia parada. ${s?.aviso.orEmpty()}"
            else "${c.nome}. ${c.pontoA.nome}: ${espera(c, "A") ?: "sem dado"} minutos. ${c.pontoB.nome}: ${espera(c, "B") ?: "sem dado"} minutos."
        return cartao
    }
    private fun detalhesTravessia(c: Travessia, lado: String? = null) {
        val s = c.situacao
        val corpo = ui.coluna()
        corpo.addView(ui.text(rotuloTipo(c.tipo), 13f, Paleta.apagado))
        corpo.addView(ui.espaco(18))
        val lados = ui.linha()
        listOf("A", "B").forEach { sd ->
            val caixa = ui.coluna().apply { setPadding(dp(14), dp(12), dp(14), dp(12)); background = ui.arredondado(if (sd == lado) Paleta.suave else Paleta.fundo, 14) }
            caixa.addView(ui.text(c.terminal(sd).nome, 13f, Paleta.apagado).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            caixa.addView(ui.espaco(4))
            caixa.addView(ui.text(valorDoLado(c, sd, 28), 15f, Paleta.tinta, true))
            if (!c.parada) caixa.addView(ui.text(nivelEspera(espera(c, sd), s?.operando ?: true).rotulo, 12f, corDoNivel(c, sd)).apply { setPadding(0, dp(4), 0, 0) })
            (if (sd == "A") c.comunidadeA else c.comunidadeB)?.let { caixa.addView(ui.text("~${it.minutosEspera} min · ${it.relatos} relato${if (it.relatos > 1) "s" else ""}", 12f, Paleta.apagado).apply { setPadding(0, dp(4), 0, 0) }) }
            lados.addView(caixa, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (sd == "B") marginStart = dp(10) })
        }
        corpo.addView(lados)
        if (s != null) {
            corpo.addView(ui.espaco(16))
            listOfNotNull(
                if (c.parada) "⛔ Travessia parada pelo painel oficial" else null,
                if (c.relatosParada > 0) textoRelatosParada(c) else null,
                if (s.operando) "${s.embarcacoes} ${if (s.embarcacoes == 1) "embarcação" else "embarcações"} em operação" else "Sem embarcação em operação",
                s.climaA?.takeIf { it.isNotBlank() }?.let { "Tempo: $it" },
                s.aviso?.takeIf { it.isNotBlank() }?.let { "⚠ $it" },
                "Painel oficial · ${tempoRelativo(s.coletadoEm)}",
            ).forEach { linha -> corpo.addView(ui.text(linha, 14f, when { linha.startsWith("⛔") || linha.contains("travessia parada") -> Paleta.perigo; linha.startsWith("⚠") -> Paleta.alerta; else -> Paleta.tinta }).apply { setPadding(0, dp(5), 0, dp(5)) }) }
        }
        val recentes = relatosRecentes(c)
        lateinit var painel: android.app.Dialog
        if (recentes.isNotEmpty()) {
            corpo.addView(ui.rotulo("Relatos recentes"))
            recentes.take(3).forEach { item -> corpo.addView(cartaoRelato(item).apply { setOnClickListener { painel.dismiss(); detalhesRelato(item) } }); corpo.addView(ui.espaco(6)) }
        }
        corpo.addView(ui.espaco(20))
        painel = ui.painel(c.nome, corpo)
        val acoes = ui.linha()
        listOf("Melhor horário" to { painel.dismiss(); historico(c) }, "Relatar" to { painel.dismiss(); abrirNovoRelato(c.id, ladoInicial = lado ?: "A") })
            .forEachIndexed { indice, (rotulo, action) ->
                acoes.addView(ui.botao(rotulo, indice == 1, action), LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (indice > 0) marginStart = dp(8) })
            }
        corpo.addView(acoes)
        painel.show()
    }

    private fun mostrarRelatos() {
        listaRelatos.removeAllViews()
        val filtros = listOf<String?>(null) + dados.travessias.map { it.id }
        val rotulos = listOf("Todas") + dados.travessias.map { it.nome }
        listaRelatos.addView(Escolha(ui, rotulos, filtros.indexOf(filtroTravessia).coerceAtLeast(0)) { filtroTravessia = filtros[it]; atualizarTela() }.view)
        listaRelatos.addView(ui.espaco(14))
        val visivel = filtrarRelatos(dados.relatos, filtroTravessia, false)
        if (visivel.isEmpty()) listaRelatos.addView(ui.text("Nenhum relato recente.\nEstá na fila? Toque em + e conte quanto está esperando.", 15f, Paleta.apagado).apply {
            gravity = Gravity.CENTER; setPadding(dp(24), dp(48), dp(24), dp(24)) })
        visivel.forEach { listaRelatos.addView(cartaoRelato(it)); listaRelatos.addView(ui.espaco(10)) }
    }
    private fun travessiaPorId(id: String) = dados.travessias.firstOrNull { it.id == id } ?: catalogoTravessias.first { it.id == id }
    // Onde foi o relato: a travessia e o lado da fila (ex.: "Santos ⇄ Guarujá · lado Santos").
    private fun localDoRelato(item: Relato) = travessiaPorId(item.travessiaId).let { "${it.nome} · lado ${it.terminal(item.lado).nome}" }
    private fun tituloRelato(item: Relato) = "${infoCategoria(item.categoria).rotulo} · ${localDoRelato(item)}"
    private fun cartaoRelato(item: Relato): View {
        val info = infoCategoria(item.categoria); val color = Color.parseColor(info.color)
        val cartao = ui.linha().apply { setPadding(dp(16), dp(14), dp(16), dp(14)); background = ui.arredondado(Color.WHITE, 18); isClickable = true; isFocusable = true; setOnClickListener { detalhesRelato(item) } }
        cartao.addView(ui.text(info.simbolo, 18f, color, true).apply { gravity = Gravity.CENTER; background = ui.arredondado(Paleta.fundo, 20) },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
        val text = ui.coluna()
        val topo = ui.linha()
        topo.addView(ui.text(info.rotulo, 15f, Paleta.tinta, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (item.autorId == autorId()) topo.addView(ui.text("Seu relato", 11f, Paleta.destaque, true).apply {
            background = ui.arredondado(Paleta.suave, 10); setPadding(dp(8), dp(3), dp(8), dp(3)) })
        text.addView(topo)
        text.addView(ui.text(localDoRelato(item), 14f, Paleta.tinta).apply { setPadding(0, dp(4), 0, 0) })
        text.addView(ui.text(listOfNotNull(item.minutosEspera?.let { "Espera de $it min" }, "enviado ${tempoRelativo(item.criadoEm)}").joinToString(" · ")
            .replaceFirstChar { it.uppercase() }, 13f, Paleta.apagado).apply { setPadding(0, dp(4), 0, 0) })
        cartao.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cartao.contentDescription = tituloRelato(item)
        return cartao
    }
    private fun detalhesRelato(item: Relato) {
        val corpo = ui.coluna()
        val travessia = travessiaPorId(item.travessiaId)
        listOf("Travessia" to travessia.nome, "Lado da fila" to travessia.terminal(item.lado).nome,
            "Espera informada" to (item.minutosEspera?.let { "$it min" } ?: "não informada"),
            (if (item.situacao == "active") "Enviado" else "Encerrado") to tempoRelativo(item.criadoEm),
        ).forEach { (rotulo, valor) ->
            val linha = ui.linha().apply { setPadding(0, dp(7), 0, dp(7)) }
            linha.addView(ui.text(rotulo, 14f, Paleta.apagado), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            linha.addView(ui.text(valor, 14f, Paleta.tinta, true))
            corpo.addView(linha)
        }
        item.descricao.takeIf { it.isNotBlank() }?.let {
            corpo.addView(ui.text("“$it”", 15f, Paleta.tinta).apply { setPadding(dp(14), dp(12), dp(14), dp(12)); background = ui.arredondado(Paleta.suave, 12)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) } })
        }
        corpo.addView(ui.text("Informação colaborativa, sem confirmação oficial.", 13f, Paleta.apagado).apply { setPadding(0, dp(14), 0, 0) })
        corpo.addView(ui.espaco(18))
        val painel = ui.painel(infoCategoria(item.categoria).rotulo, corpo)
        val acoes = ui.linha()
        acoes.addView(ui.botao("Ver no mapa") { painel.dismiss(); aba = 1; atualizarTela(); painelMapa.focar(item.latitude, item.longitude) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        if (item.situacao == "active" && item.autorId == autorId()) acoes.addView(ui.botao("Encerrar", true) {
            painel.dismiss()
            val senha = estado.geracao
            remoto?.encerrar(item.id, { if (estado.aceita(senha)) { recarregar(); mostrarAviso("Relato encerrado.") } }, ::falha)
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        corpo.addView(acoes)
        painel.show()
    }
    private fun abrirMarcador(id: String) {
        val partes = id.split(":")
        if (partes[0] == "report") dados.relatos.firstOrNull { it.id == partes[1] }?.let(::detalhesRelato)
        else detalhesTravessia(travessiaPorId(partes[1]), partes[2])
    }

    private fun historico(c: Travessia) {
        val show = { h: Historico ->
            val corpo = ui.coluna()
            if (h.porHora.isEmpty()) corpo.addView(ui.text("Ainda não há leituras suficientes. Volte mais tarde.", 15f, Paleta.apagado).apply { setPadding(0, dp(12), 0, dp(12)) })
            else {
                corpo.addView(ui.text("Espera média por hora, últimos 7 dias", 13f, Paleta.apagado))
                corpo.addView(ui.espaco(16)); corpo.addView(GraficoHistorico(this, h)); corpo.addView(ui.espaco(16))
                listOf("A" to h.melhorA, "B" to h.melhorB).forEach { (lado, hora) ->
                    if (hora == null) return@forEach
                    val media = h.porHora.first { it.hora == hora }.let { if (lado == "A") it.esperaA else it.esperaB }
                    val linha = ui.linha().apply { setPadding(0, dp(6), 0, dp(6)) }
                    linha.addView(ui.text("■", 14f, if (lado == "A") Paleta.marinho else Paleta.destaque).apply { setPadding(0, 0, dp(10), 0) })
                    linha.addView(ui.text("Saindo de ${c.terminal(lado).nome}", 15f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    linha.addView(ui.text("${hora}h · ~$media min", 15f, Color.parseColor(NivelEspera.BAIXA.color), true))
                    corpo.addView(linha)
                }
            }
            ui.painel("Melhor horário", corpo).show()
        }
        val senha = estado.geracao
        remoto?.historico(c.id, { if (estado.aceita(senha)) show(it) }, ::falha)
    }
    private fun abrirNovoRelato(travessiaId: String?, lat: Double? = null, lng: Double? = null, ladoInicial: String = "A") {
        val corpo = ui.coluna()
        var travessia = catalogoTravessias.firstOrNull { it.id == travessiaId } ?: catalogoTravessias.first()
        lateinit var lado: Escolha
        corpo.addView(ui.rotulo("Travessia"))
        corpo.addView(Escolha(ui, catalogoTravessias.map { it.nome }, catalogoTravessias.indexOf(travessia)) {
            travessia = catalogoTravessias[it]; lado.trocarRotulos(listOf("Em ${travessia.pontoA.nome}", "Em ${travessia.pontoB.nome}"))
        }.view)
        corpo.addView(ui.rotulo("Onde você está"))
        lado = Escolha(ui, listOf("Em ${travessia.pontoA.nome}", "Em ${travessia.pontoB.nome}"), if (ladoInicial == "B") 1 else 0)
        corpo.addView(lado.view)
        corpo.addView(ui.rotulo("O que está acontecendo"))
        val categoria = Escolha(ui, categorias.map { it.rotulo })
        corpo.addView(categoria.view)
        corpo.addView(ui.rotulo("Detalhes (opcional)"))
        val campoEspera = ui.entrada("Minutos de espera").apply { inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(InputFilter.LengthFilter(3)) }
        corpo.addView(campoEspera); corpo.addView(ui.espaco(8))
        val descricao = ui.entrada("Ex.: a fila chega até o canal 6").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; filters = arrayOf(InputFilter.LengthFilter(400)) }
        corpo.addView(descricao)
        if (lat != null) corpo.addView(ui.text("📍 Ponto escolhido no mapa", 13f, Paleta.apagado).apply { setPadding(0, dp(12), 0, 0) })
        corpo.addView(ui.espaco(24))
        val painel = ui.painel("Novo relato", corpo)
        corpo.addView(ui.botao("Publicar", true) {
            val letraLado = if (lado.selecionada == 1) "B" else "A"
            val terminal = travessia.terminal(letraLado)
            val textoEspera = campoEspera.text.toString().trim()
            val espera = if (textoEspera.isEmpty()) null else textoEspera.toIntOrNull() ?: -1
            val ponto = if (lat != null && lng != null) lat to lng else terminal.latitude to terminal.longitude
            val cat = categorias[categoria.selecionada].key
            val error = validarRelato(travessia.id, letraLado, cat, espera, descricao.text.toString(), ponto.first, ponto.second)
            if (error != null) { mostrarAviso(error); return@botao }
            val entrada = NovoRelato(travessia.id, letraLado, cat, espera, descricao.text.toString(), ponto.first, ponto.second)
            val senha = estado.geracao
            remoto?.criar(entrada, { if (estado.aceita(senha)) { painel.dismiss(); aba = 2; recarregar(); mostrarAviso("Relato publicado.") } },
                { message -> if (estado.aceita(senha)) mostrarAviso(message) })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        painel.show()
    }
    // Ajustes: o normal é o servidor oficial. "Outro servidor" é para quem roda o sistema no próprio computador.
    private fun abrirAjustes() {
        val corpo = ui.coluna()
        corpo.addView(ui.text("De onde o app busca os dados", 13f, Paleta.apagado))
        corpo.addView(ui.espaco(14))
        val atual = prefs.getString("last_server", SERVIDOR_OFICIAL).orEmpty()
        var personalizado = atual != SERVIDOR_OFICIAL
        val endereco = ui.entrada("http://192.168.0.10:8080", if (personalizado) atual else "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; isSingleLine = true }
        val error = ui.text("", 13f, Paleta.perigo).apply { setPadding(dp(4), dp(8), 0, 0); visibility = View.GONE }
        val opcoes = listOf(
            Triple("servers", "Servidor oficial", "sedda.com.br · dados reais e relatos de todos"),
            Triple("server", "Outro servidor", "O sistema rodando no seu computador (Rodar local)"),
        ).mapIndexed { indice, (icone, titulo, detalhe) ->
            ui.linha().apply {
                setPadding(dp(14), dp(14), dp(14), dp(14)); isClickable = true; contentDescription = titulo
                addView(ImageView(this@MainActivity).apply { setImageDrawable(DesenhoIcone(icone, Paleta.destaque)) }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) })
                addView(ui.coluna().apply { addView(ui.text(titulo, 15f, Paleta.tinta, true)); addView(ui.text(detalhe, 13f, Paleta.apagado).apply { setPadding(0, dp(3), 0, 0) }) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }.also { linha -> corpo.addView(linha, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }) }
        }
        fun pintar() {
            opcoes.forEachIndexed { i, linha -> val ativo = (i == 1) == personalizado; linha.background = ui.arredondado(if (ativo) Paleta.suave else Color.WHITE, 14, if (ativo) Paleta.destaque else Paleta.linha) }
            endereco.visibility = if (personalizado) View.VISIBLE else View.GONE; error.visibility = View.GONE
        }
        opcoes.forEachIndexed { i, linha -> linha.setOnClickListener { personalizado = i == 1; pintar() } }
        corpo.addView(endereco); corpo.addView(error); pintar()
        corpo.addView(ui.espaco(12))
        corpo.addView(ui.text("Dados: painel das Travessias Litorâneas (SEMIL-SP). Relatos são colaborativos. Mapa © OpenStreetMap.", 12f, Paleta.apagado))
        corpo.addView(ui.espaco(20))
        val painel = ui.painel("Ajustes", corpo)
        corpo.addView(ui.botao("Aplicar", true) {
            val url = if (personalizado) runCatching { normalizarServidor(endereco.text.toString()) }
                .getOrElse { error.text = it.message; error.visibility = View.VISIBLE; return@botao } else SERVIDOR_OFICIAL
            prefs.edit().putString("last_server", url).apply()
            painel.dismiss(); ativar(url)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        painel.show()
    }
    private fun autorId() = remoto?.autorId.orEmpty()
    private fun falha(message: String) { erroConexao = true; atualizarTela(); mostrarAviso(message) }
    private fun mostrarAviso(valor: String) { Toast.makeText(this, valor, Toast.LENGTH_LONG).show() }
    private fun tempoRelativo(valor: String?): String = runCatching {
        val minutos = Duration.between(Instant.parse(valor), Instant.now()).toMinutes().coerceAtLeast(0)
        if (minutos < 1) "agora" else if (minutos < 60) "há $minutos min" else if (minutos < 1440) "há ${minutos / 60} h" else "há ${minutos / 1440} dias"
    }.getOrDefault("—")

    @Suppress("MissingPermission", "DEPRECATION")
    private fun localizar() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            localizacaoPendente = { localizar() }; requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 41); return
        }
        val gerenciador = getSystemService(LOCATION_SERVICE) as LocationManager
        val provedores = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { runCatching { gerenciador.isProviderEnabled(it) }.getOrDefault(false) }
        val conhecidos = provedores.mapNotNull { runCatching { gerenciador.getLastKnownLocation(it) }.getOrNull() }
            .filter { localizacaoRecente((SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000L) }.maxByOrNull { it.time }
        if (conhecidos != null) { painelMapa.focar(conhecidos.latitude, conhecidos.longitude); return }
        if (provedores.isEmpty()) { mostrarAviso("Ative a localização ou escolha um ponto no mapa."); return }
        mostrarAviso("Buscando sua posição…")
        ouvinteLocalizacao?.let { gerenciador.removeUpdates(it) }
        val ouvinte = object : LocationListener {
            override fun onLocationChanged(localizacao: Location) { gerenciador.removeUpdates(this); ouvinteLocalizacao = null; painelMapa.focar(localizacao.latitude, localizacao.longitude) }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Android") override fun onStatusChanged(provider: String?, situacao: Int, extras: Bundle?) {}
        }
        ouvinteLocalizacao = ouvinte
        runCatching { gerenciador.requestLocationUpdates(provedores.first(), 0L, 0f, ouvinte, Looper.getMainLooper()) }.onFailure { mostrarAviso("Não foi possível obter a posição.") }
        agendador.postDelayed({ if (ouvinteLocalizacao === ouvinte) { gerenciador.removeUpdates(ouvinte); ouvinteLocalizacao = null; mostrarAviso("Posição indisponível.") } }, 10000)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41) { if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) localizacaoPendente?.invoke() else mostrarAviso("Sem problema: escolha o ponto no mapa."); localizacaoPendente = null }
    }
    override fun onStart() { super.onStart(); painelMapa.start(); agendador.postDelayed(atualizacaoPeriodica, 30_000); if (aba == 3) bastidores.show() }
    override fun onResume() { super.onResume(); painelMapa.resume(); recarregar() }
    override fun onPause() { painelMapa.pause(); super.onPause() }
    override fun onStop() { agendador.removeCallbacks(atualizacaoPeriodica); bastidores.hide(); painelMapa.stop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); painelMapa.lowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); painelMapa.save(outState) }
    override fun onDestroy() { remoto?.fechar(); agendador.removeCallbacksAndMessages(null); ouvinteLocalizacao?.let { runCatching { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(it) } }; painelMapa.destroy(); super.onDestroy() }
}
