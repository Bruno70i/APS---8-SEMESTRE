package br.com.viaagora

import java.time.Duration
import java.time.Instant

// Modelos de dados do app: travessias, situação de cada lado, relatos, categorias e histórico.
data class Terminal(val nome: String, val endereco: String, val latitude: Double, val longitude: Double)
data class SituacaoTravessia(
    val esperaA: Int, val esperaB: Int, val embarcacoes: Int, val operando: Boolean, val climaA: String?, val climaB: String?,
    val periodo: String?, val aviso: String?, val fonte: String, val coletadoEm: String, val atrasadoNoServidor: Boolean,
) {
    // Atrasado se o servidor disse ou se a leitura já tem mais de 10 min (por exemplo, cache aberto sem internet).
    val atrasado: Boolean get() = atrasadoNoServidor ||
        runCatching { Duration.between(Instant.parse(coletadoEm), Instant.now()) > Duration.ofMinutes(10) }.getOrDefault(true)
}
data class EsperaComunidade(val minutosEspera: Int, val relatos: Int)
data class Travessia(
    val id: String, val nome: String, val tipo: String, val pontoA: Terminal, val pontoB: Terminal,
    val situacao: SituacaoTravessia?, val comunidadeA: EsperaComunidade?, val comunidadeB: EsperaComunidade?, val relatosAtivos: Int,
    val relatosParada: Int = 0, val rota: List<Pair<Double, Double>> = emptyList(),
) {
    fun terminal(lado: String) = if (lado == "A") pontoA else pontoB
    // Parada pelo painel oficial (sem embarcação ou aviso de interrupção).
    val parada: Boolean get() = situacao?.operando == false
}
data class InfoFonte(val modo: String?, val disponivel: Boolean, val ultimoSucessoEm: String?, val ultimoErro: String?)
data class Panorama(val travessias: List<Travessia>, val relatos: List<Relato>, val fonte: InfoFonte)
data class Relato(
    val id: String, val autorId: String, val travessiaId: String, val lado: String, val categoria: String, val minutosEspera: Int?,
    val descricao: String, val latitude: Double, val longitude: Double, val situacao: String, val versao: Int,
    val criadoEm: String, val atualizadoEm: String,
)
data class MediaPorHora(val hora: Int, val esperaA: Int, val esperaB: Int)
data class Historico(val travessiaId: String, val porHora: List<MediaPorHora>, val melhorA: Int?, val melhorB: Int?)
data class Categoria(val key: String, val rotulo: String, val simbolo: String, val color: String)

val categorias = listOf(
    Categoria("fila", "Fila grande", "≡", "#C2410C"),
    Categoria("embarque_lento", "Embarque lento", "◷", "#B45309"),
    Categoria("interrompida", "Travessia parada", "■", "#B91C1C"),
    Categoria("clima", "Neblina ou mar agitado", "≈", "#1D4ED8"),
    Categoria("acidente", "Acidente no acesso", "!", "#7C3AED"),
)
fun infoCategoria(key: String): Categoria = categorias.firstOrNull { it.key == key } ?: categorias.first()

// Mesmo catálogo do servidor (terminais e rotas das linhas de balsa do OpenStreetMap); usado enquanto o
// servidor não responde.
val catalogoTravessias = listOf(
    Travessia("santos-guaruja", "Santos ⇄ Guarujá", "veiculos",
        Terminal("Santos", "Praça Gago Coutinho, 525 - Ponta da Praia", -23.98692, -46.29379),
        Terminal("Guarujá", "Av. Ademar de Barros, 3300", -23.98832, -46.28944), null, null, null, 0,
        rota = listOf(-23.98692 to -46.29379, -23.98832 to -46.28944)),
    Travessia("santos-vicente-de-carvalho", "Santos ⇄ Vicente de Carvalho", "pedestres",
        Terminal("Santos", "Praça da República - Centro", -23.93171, -46.32451),
        Terminal("Vicente de Carvalho", "Av. Thiago Ferreira", -23.93964, -46.30827), null, null, null, 0,
        rota = listOf(-23.93171 to -46.32451, -23.93075 to -46.32423, -23.93068 to -46.32406, -23.93152 to -46.31825, -23.93313 to -46.31383,
            -23.93512 to -46.31194, -23.94001 to -46.31023, -23.93963 to -46.30835, -23.93964 to -46.30827)),
    Travessia("bertioga-guaruja", "Bertioga ⇄ Guarujá", "veiculos",
        Terminal("Bertioga", "Av. Henrique Costabile, 50 - Jardim Veleiros", -23.85639, -46.14465),
        Terminal("Guarujá", "Estrada Guarujá-Bertioga, km 29", -23.86028, -46.14337), null, null, null, 0,
        rota = listOf(-23.85639 to -46.14465, -23.86028 to -46.14337)),
)

enum class NivelEspera(val rotulo: String, val color: String) {
    BAIXA("Tranquila", "#15803D"), MEDIA("Moderada", "#B45309"), ALTA("Longa", "#B91C1C"), FECHADA("Sem operação", "#64748B"), DESCONHECIDA("Sem dados", "#64748B")
}
fun nivelEspera(minutos: Int?, operando: Boolean = true): NivelEspera = when {
    !operando -> NivelEspera.FECHADA
    minutos == null -> NivelEspera.DESCONHECIDA
    minutos <= 20 -> NivelEspera.BAIXA
    minutos <= 45 -> NivelEspera.MEDIA
    else -> NivelEspera.ALTA
}
fun rotuloTipo(tipo: String) = if (tipo == "pedestres") "Pedestres e ciclistas" else "Veículos e pedestres"

fun localizacaoRecente(idadeMillis: Long): Boolean = idadeMillis in 0L..120_000L
fun validarRelato(travessiaId: String, lado: String, categoria: String, minutosEspera: Int?, descricao: String, latitude: Double, longitude: Double): String? = when {
    catalogoTravessias.none { it.id == travessiaId } -> "Escolha a travessia."
    lado !in listOf("A", "B") -> "Escolha de que lado você está."
    categorias.none { it.key == categoria } -> "Escolha o tipo de relato."
    minutosEspera != null && minutosEspera !in 0..600 -> "A espera deve ficar entre 0 e 600 minutos."
    descricao.trim().length > 400 -> "Use no máximo 400 caracteres."
    !latitude.isFinite() || latitude !in -90.0..90.0 || !longitude.isFinite() || longitude !in -180.0..180.0 -> "Escolha um ponto válido no mapa."
    else -> null
}
fun filtrarRelatos(dados: List<Relato>, travessiaId: String?, incluirEncerrados: Boolean): List<Relato> =
    dados.filter { (travessiaId == null || it.travessiaId == travessiaId) && (incluirEncerrados || it.situacao == "active") }
