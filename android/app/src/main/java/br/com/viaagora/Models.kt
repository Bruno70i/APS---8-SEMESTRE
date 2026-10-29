package br.com.viaagora

import java.time.Duration
import java.time.Instant

data class Terminal(val name: String, val address: String, val latitude: Double, val longitude: Double)
data class CrossingStatus(
    val waitA: Int, val waitB: Int, val boats: Int, val operating: Boolean, val weatherA: String?, val weatherB: String?,
    val period: String?, val notice: String?, val source: String, val collectedAt: String, val serverStale: Boolean,
) {
    // Atrasado se o servidor disse ou se a leitura já tem mais de 10 min (por exemplo, cache aberto sem internet).
    val stale: Boolean get() = serverStale ||
        runCatching { Duration.between(Instant.parse(collectedAt), Instant.now()) > Duration.ofMinutes(10) }.getOrDefault(true)
}
data class CommunityWait(val waitMinutes: Int, val reports: Int)
data class Crossing(
    val id: String, val name: String, val kind: String, val pointA: Terminal, val pointB: Terminal,
    val status: CrossingStatus?, val communityA: CommunityWait?, val communityB: CommunityWait?, val activeReports: Int,
    val stopReports: Int = 0, val route: List<Pair<Double, Double>> = emptyList(),
) {
    fun terminal(side: String) = if (side == "A") pointA else pointB
    // Parada pelo painel oficial (sem embarcação ou aviso de interrupção).
    val stopped: Boolean get() = status?.operating == false
}
data class SourceInfo(val mode: String?, val available: Boolean, val lastSuccessAt: String?, val lastError: String?)
data class Snapshot(val crossings: List<Crossing>, val reports: List<Report>, val source: SourceInfo)
data class Report(
    val id: String, val authorId: String, val crossingId: String, val side: String, val category: String, val waitMinutes: Int?,
    val description: String, val latitude: Double, val longitude: Double, val status: String, val version: Int,
    val createdAt: String, val updatedAt: String,
)
data class HourAverage(val hour: Int, val waitA: Int, val waitB: Int)
data class History(val crossingId: String, val hourly: List<HourAverage>, val bestA: Int?, val bestB: Int?)
data class Category(val key: String, val label: String, val symbol: String, val color: String)

val categories = listOf(
    Category("fila", "Fila grande", "≡", "#C2410C"),
    Category("embarque_lento", "Embarque lento", "◷", "#B45309"),
    Category("interrompida", "Travessia parada", "■", "#B91C1C"),
    Category("clima", "Neblina ou mar agitado", "≈", "#1D4ED8"),
    Category("acidente", "Acidente no acesso", "!", "#7C3AED"),
)
fun categoryInfo(key: String): Category = categories.firstOrNull { it.key == key } ?: categories.first()

// Mesmo catálogo do servidor (terminais e rotas das linhas de balsa do OpenStreetMap); usado enquanto o
// servidor não responde.
val crossingCatalog = listOf(
    Crossing("santos-guaruja", "Santos ⇄ Guarujá", "veiculos",
        Terminal("Santos", "Praça Gago Coutinho, 525 - Ponta da Praia", -23.98692, -46.29379),
        Terminal("Guarujá", "Av. Ademar de Barros, 3300", -23.98832, -46.28944), null, null, null, 0,
        route = listOf(-23.98692 to -46.29379, -23.98832 to -46.28944)),
    Crossing("santos-vicente-de-carvalho", "Santos ⇄ Vicente de Carvalho", "pedestres",
        Terminal("Santos", "Praça da República - Centro", -23.93171, -46.32451),
        Terminal("Vicente de Carvalho", "Av. Thiago Ferreira", -23.93964, -46.30827), null, null, null, 0,
        route = listOf(-23.93171 to -46.32451, -23.93075 to -46.32423, -23.93068 to -46.32406, -23.93152 to -46.31825, -23.93313 to -46.31383,
            -23.93512 to -46.31194, -23.94001 to -46.31023, -23.93963 to -46.30835, -23.93964 to -46.30827)),
    Crossing("bertioga-guaruja", "Bertioga ⇄ Guarujá", "veiculos",
        Terminal("Bertioga", "Av. Henrique Costabile, 50 - Jardim Veleiros", -23.85639, -46.14465),
        Terminal("Guarujá", "Estrada Guarujá-Bertioga, km 29", -23.86028, -46.14337), null, null, null, 0,
        route = listOf(-23.85639 to -46.14465, -23.86028 to -46.14337)),
)

enum class WaitLevel(val label: String, val color: String) {
    LOW("Tranquila", "#15803D"), MEDIUM("Moderada", "#B45309"), HIGH("Longa", "#B91C1C"), CLOSED("Sem operação", "#64748B"), UNKNOWN("Sem dados", "#64748B")
}
fun waitLevel(minutes: Int?, operating: Boolean = true): WaitLevel = when {
    !operating -> WaitLevel.CLOSED
    minutes == null -> WaitLevel.UNKNOWN
    minutes <= 20 -> WaitLevel.LOW
    minutes <= 45 -> WaitLevel.MEDIUM
    else -> WaitLevel.HIGH
}
fun kindLabel(kind: String) = if (kind == "pedestres") "Pedestres e ciclistas" else "Veículos e pedestres"

fun isRecentLocation(ageMillis: Long): Boolean = ageMillis in 0L..120_000L
fun validateReport(crossingId: String, side: String, category: String, waitMinutes: Int?, description: String, latitude: Double, longitude: Double): String? = when {
    crossingCatalog.none { it.id == crossingId } -> "Escolha a travessia."
    side !in listOf("A", "B") -> "Escolha de que lado você está."
    categories.none { it.key == category } -> "Escolha o tipo de relato."
    waitMinutes != null && waitMinutes !in 0..600 -> "A espera deve ficar entre 0 e 600 minutos."
    description.trim().length > 400 -> "Use no máximo 400 caracteres."
    !latitude.isFinite() || latitude !in -90.0..90.0 || !longitude.isFinite() || longitude !in -180.0..180.0 -> "Escolha um ponto válido no mapa."
    else -> null
}
fun filterReports(data: List<Report>, crossingId: String?, includeResolved: Boolean): List<Report> =
    data.filter { (crossingId == null || it.crossingId == crossingId) && (includeResolved || it.status == "active") }
