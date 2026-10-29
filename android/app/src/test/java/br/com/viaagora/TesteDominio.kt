package br.com.viaagora

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

// Testes das regras do app (rodam no computador, sem emulador).
class TesteDominio {
    private val ponto = -23.98 to -46.30
    private fun valido(travessia: String = "santos-guaruja", lado: String = "A", categoria: String = "fila", espera: Int? = 30, text: String = "") =
        validarRelato(travessia, lado, categoria, espera, text, ponto.first, ponto.second)

    @Test fun validacaoAceitaCamposOpcionaisERecusaValoresRuins() {
        assertNull(valido())
        assertNull(valido(espera = null, text = ""))
        assertNotNull(valido(travessia = "sao-sebastiao-ilhabela"))
        assertNotNull(valido(lado = "C"))
        assertNotNull(valido(categoria = "obra"))
        assertNotNull(valido(espera = 601))
        assertNotNull(valido(espera = -1))
        assertNotNull(valido(text = "x".repeat(401)))
        assertNotNull(validarRelato("santos-guaruja", "A", "fila", null, "", Double.NaN, -46.0))
        assertNotNull(validarRelato("santos-guaruja", "A", "fila", null, "", 95.0, -46.0))
    }
    @Test fun normalizacaoDoServidorRecusaCredenciaisEMantemOCaminho() {
        assertEquals("http://192.168.1.10:4310", normalizarServidor("  http://192.168.1.10:4310/ "))
        assertEquals("https://example.com/api", normalizarServidor("https://EXAMPLE.com/api/"))
        for (ruim in listOf("", "ftp://example.com", "http://user:pass@example.com", "https://example.com?token=secret", "localhost:4310")) {
            assertTrue(runCatching { normalizarServidor(ruim) }.isFailure)
        }
    }
    @Test fun trocarDeServidorDescartaRespostasDoAnterior() {
        val estado = EstadoApp()
        estado.selecionar("http://192.168.1.10:8080")
        val senha = estado.geracao
        assertTrue(estado.aceita(senha))
        estado.selecionar(SERVIDOR_OFICIAL)
        assertFalse(estado.aceita(senha))
        assertEquals(SERVIDOR_OFICIAL, estado.servidor)
    }
    @Test fun niveisDeEsperaUsamLimitesFixosERespeitamTravessiaFechada() {
        assertEquals(NivelEspera.BAIXA, nivelEspera(20))
        assertEquals(NivelEspera.MEDIA, nivelEspera(21))
        assertEquals(NivelEspera.MEDIA, nivelEspera(45))
        assertEquals(NivelEspera.ALTA, nivelEspera(46))
        assertEquals(NivelEspera.DESCONHECIDA, nivelEspera(null))
        assertEquals(NivelEspera.FECHADA, nivelEspera(5, operando = false))
    }
    @Test fun filtroEscondeEncerradosEOutrasTravessias() {
        val a = Relato("1", "me", "santos-guaruja", "A", "fila", 30, "", -23.98, -46.30, "active", 1, "2026-10-28T12:00:00Z", "2026-10-28T12:00:00Z")
        val dados = listOf(a, a.copy(id = "2", travessiaId = "bertioga-guaruja"), a.copy(id = "3", situacao = "resolved"))
        assertEquals(listOf("1"), filtrarRelatos(dados, "santos-guaruja", false).map { it.id })
        assertEquals(3, filtrarRelatos(dados, null, true).size)
    }
    @Test fun travessiaParadaEDetectadaPeloPainelOficial() {
        val running = SituacaoTravessia(15, 15, 3, true, null, null, null, null, "oficial", Instant.now().toString(), false)
        val santos = catalogoTravessias.first()
        assertTrue(santos.copy(situacao = running.copy(operando = false, embarcacoes = 0)).parada)
        assertTrue(!santos.copy(situacao = running).parada)
    }
    @Test fun rotaDeCadaTravessiaComecaETerminaNosTerminais() {
        for (c in catalogoTravessias) {
            assertEquals(c.pontoA.latitude to c.pontoA.longitude, c.rota.first())
            assertEquals(c.pontoB.latitude to c.pontoB.longitude, c.rota.last())
        }
    }
    @Test fun leituraComMaisDeDezMinutosFicaAtrasadaMesmoSeOServidorDisserQueNao() {
        val fresh = SituacaoTravessia(15, 15, 3, true, null, null, null, null, "oficial", Instant.now().toString(), false)
        assertFalse(fresh.atrasado)
        assertTrue(fresh.copy(coletadoEm = Instant.now().minusSeconds(11 * 60).toString()).atrasado)
        assertTrue(fresh.copy(atrasadoNoServidor = true).atrasado)
    }
    @Test fun estadoDoSistemaELidoDoContratoDoServidor() {
        val json = """{"servedBy":"api-1","nodes":[{"node":"api-2","role":"api","alive":false,"ageMs":40000,"info":{"clients":3,"broker":true}},
            {"node":"collector","role":"collector","alive":true,"ageMs":2000,"info":{"lastOk":false}}],"services":{"database":"up","broker":"up"},
            "outbox":{"pending":2,"lastHour":9},"counters":{"idempotentReplays":4},"collections":{"last24h":1440,"total":5000},
            "events":[{"eventId":"e1","type":"report.created","origin":"api-2","crossingId":"santos-guaruja","createdAt":"2026-10-29T03:00:00Z",
            "publishedAt":"2026-10-29T03:00:00.400Z","deliveries":[{"replica":"api-1","clients":3,"deliveredAt":"2026-10-29T03:00:00.405Z"}]}]}"""
        val s = lerSistema(json)
        assertEquals("api-1", s.atendidoPor); assertTrue(s.brokerNoAr); assertEquals(2, s.pendentes); assertEquals(4, s.reenvios)
        assertEquals(false, s.processos.first().vivo); assertEquals(3, s.processos.first().celulares); assertEquals(false, s.processos.last().coletorOk)
        assertEquals("api-2", s.eventos.single().origem); assertEquals(1, s.eventos.single().entregas.size)
    }
    @Test fun rastroDeUmRelatoSegueOCaminhoRealComOsTemposMedidos() {
        val evento = RastroEvento("e1", "report.created", "api-2", "santos-guaruja", "2026-10-29T03:00:00Z", "2026-10-29T03:00:00.400Z",
            listOf(Entrega("api-2", 2, "2026-10-29T03:00:00.409Z"), Entrega("api-1", 3, "2026-10-29T03:00:00.405Z")))
        val passos = passosDoRastro(evento)
        assertEquals(listOf("cel2" to "api2"), passos.first().saltos)
        assertEquals(listOf("api2" to "db"), passos[1].saltos)
        assertEquals(400L, passos.first { it.saltos == listOf("wk" to "mq") }.ms)
        assertEquals(listOf("mq" to "api1", "mq" to "api2"), passos[4].saltos)
        assertEquals(9L, passos[4].ms)
        assertEquals(409L, tempoTotal(evento))
    }
    @Test fun eventoNaoPublicadoParaNaOutboxEEventoDoColetorComecaNoSiteOficial() {
        val esperando = passosDoRastro(RastroEvento("e2", "report.created", "api-1", null, "2026-10-29T03:00:00Z", null, emptyList()))
        assertTrue(esperando.last().saltos.isEmpty()); assertTrue("Tolerância a falhas" in esperando.last().conceitos)
        val coletado = passosDoRastro(RastroEvento("e3", "crossing.updated", null, "bertioga-guaruja", "2026-10-29T03:00:00Z", null, emptyList()))
        assertEquals(listOf("semil" to "col"), coletado.first().saltos)
        val perdido = RastroEvento("e4", "report.created", "api-1", null, "2026-10-29T03:00:00Z", "2026-10-29T03:00:01Z", emptyList())
        assertEquals("Entregue por ressincronização", passosDoRastro(perdido, Instant.parse("2026-10-29T03:00:30Z")).last().rotulo)
        assertEquals("Worker → RabbitMQ", passosDoRastro(perdido, Instant.parse("2026-10-29T03:00:02Z")).last().rotulo)
    }
    @Test fun localizacaoAntigaOuFuturaNaoEUsadaComoPosicaoAtual() {
        assertTrue(localizacaoRecente(60_000))
        assertFalse(localizacaoRecente(120_001))
        assertFalse(localizacaoRecente(-1))
    }
}
