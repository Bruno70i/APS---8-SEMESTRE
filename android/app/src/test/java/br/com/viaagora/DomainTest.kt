package br.com.viaagora

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DomainTest {
    private val point = -23.98 to -46.30
    private fun valid(crossing: String = "santos-guaruja", side: String = "A", category: String = "fila", wait: Int? = 30, text: String = "") =
        validateReport(crossing, side, category, wait, text, point.first, point.second)

    @Test fun validationAcceptsOptionalFieldsAndRejectsBadValues() {
        assertNull(valid())
        assertNull(valid(wait = null, text = ""))
        assertNotNull(valid(crossing = "sao-sebastiao-ilhabela"))
        assertNotNull(valid(side = "C"))
        assertNotNull(valid(category = "obra"))
        assertNotNull(valid(wait = 601))
        assertNotNull(valid(wait = -1))
        assertNotNull(valid(text = "x".repeat(401)))
        assertNotNull(validateReport("santos-guaruja", "A", "fila", null, "", Double.NaN, -46.0))
        assertNotNull(validateReport("santos-guaruja", "A", "fila", null, "", 95.0, -46.0))
    }
    @Test fun serverNormalizationRejectsCredentialsAndPreservesBasePaths() {
        assertEquals("http://192.168.1.10:4310", normalizeServer("  http://192.168.1.10:4310/ "))
        assertEquals("https://example.com/api", normalizeServer("https://EXAMPLE.com/api/"))
        for (bad in listOf("", "ftp://example.com", "http://user:pass@example.com", "https://example.com?token=secret", "localhost:4310")) {
            assertTrue(runCatching { normalizeServer(bad) }.isFailure)
        }
    }
    @Test fun changingServerRejectsResultsFromThePreviousOne() {
        val state = AppState()
        state.select("http://192.168.1.10:8080")
        val ticket = state.generation
        assertTrue(state.accepts(ticket))
        state.select(PRODUCTION_SERVER)
        assertFalse(state.accepts(ticket))
        assertEquals(PRODUCTION_SERVER, state.server)
    }
    @Test fun waitLevelsUseFixedThresholdsAndHonorClosedCrossings() {
        assertEquals(WaitLevel.LOW, waitLevel(20))
        assertEquals(WaitLevel.MEDIUM, waitLevel(21))
        assertEquals(WaitLevel.MEDIUM, waitLevel(45))
        assertEquals(WaitLevel.HIGH, waitLevel(46))
        assertEquals(WaitLevel.UNKNOWN, waitLevel(null))
        assertEquals(WaitLevel.CLOSED, waitLevel(5, operating = false))
    }
    @Test fun filterHidesResolvedAndOtherCrossings() {
        val a = Report("1", "me", "santos-guaruja", "A", "fila", 30, "", -23.98, -46.30, "active", 1, "2026-10-28T12:00:00Z", "2026-10-28T12:00:00Z")
        val data = listOf(a, a.copy(id = "2", crossingId = "bertioga-guaruja"), a.copy(id = "3", status = "resolved"))
        assertEquals(listOf("1"), filterReports(data, "santos-guaruja", false).map { it.id })
        assertEquals(3, filterReports(data, null, true).size)
    }
    @Test fun stoppedCrossingIsDetectedFromTheOfficialPanel() {
        val running = CrossingStatus(15, 15, 3, true, null, null, null, null, "oficial", Instant.now().toString(), false)
        val santos = crossingCatalog.first()
        assertTrue(santos.copy(status = running.copy(operating = false, boats = 0)).stopped)
        assertTrue(!santos.copy(status = running).stopped)
    }
    @Test fun everyCrossingRouteStartsAndEndsAtItsTerminals() {
        for (c in crossingCatalog) {
            assertEquals(c.pointA.latitude to c.pointA.longitude, c.route.first())
            assertEquals(c.pointB.latitude to c.pointB.longitude, c.route.last())
        }
    }
    @Test fun aReadingOlderThanTenMinutesIsStaleEvenIfTheServerSaidItWasFresh() {
        val fresh = CrossingStatus(15, 15, 3, true, null, null, null, null, "oficial", Instant.now().toString(), false)
        assertFalse(fresh.stale)
        assertTrue(fresh.copy(collectedAt = Instant.now().minusSeconds(11 * 60).toString()).stale)
        assertTrue(fresh.copy(serverStale = true).stale)
    }
    @Test fun systemStateIsDecodedFromTheServerContract() {
        val json = """{"servedBy":"api-1","nodes":[{"node":"api-2","role":"api","alive":false,"ageMs":40000,"info":{"clients":3,"broker":true}},
            {"node":"collector","role":"collector","alive":true,"ageMs":2000,"info":{"lastOk":false}}],"services":{"database":"up","broker":"up"},
            "outbox":{"pending":2,"lastHour":9},"counters":{"idempotentReplays":4},"collections":{"last24h":1440,"total":5000},
            "events":[{"eventId":"e1","type":"report.created","origin":"api-2","crossingId":"santos-guaruja","createdAt":"2026-10-29T03:00:00Z",
            "publishedAt":"2026-10-29T03:00:00.400Z","deliveries":[{"replica":"api-1","clients":3,"deliveredAt":"2026-10-29T03:00:00.405Z"}]}]}"""
        val s = decodeSystem(json)
        assertEquals("api-1", s.servedBy); assertTrue(s.brokerUp); assertEquals(2, s.pending); assertEquals(4, s.replays)
        assertEquals(false, s.nodes.first().alive); assertEquals(3, s.nodes.first().clients); assertEquals(false, s.nodes.last().collectorOk)
        assertEquals("api-2", s.events.single().origin); assertEquals(1, s.events.single().deliveries.size)
    }
    @Test fun aReportTraceFollowsTheRealPathWithMeasuredTimes() {
        val event = EventTrace("e1", "report.created", "api-2", "santos-guaruja", "2026-10-29T03:00:00Z", "2026-10-29T03:00:00.400Z",
            listOf(Delivery("api-2", 2, "2026-10-29T03:00:00.409Z"), Delivery("api-1", 3, "2026-10-29T03:00:00.405Z")))
        val steps = traceSteps(event)
        assertEquals(listOf("cel2" to "api2"), steps.first().hops)
        assertEquals(listOf("api2" to "db"), steps[1].hops)
        assertEquals(400L, steps.first { it.hops == listOf("wk" to "mq") }.ms)
        assertEquals(listOf("mq" to "api1", "mq" to "api2"), steps[4].hops)
        assertEquals(9L, steps[4].ms)
        assertEquals(409L, totalMillis(event))
    }
    @Test fun anUnpublishedEventStopsAtTheOutboxAndCollectorEventsStartAtTheOfficialSite() {
        val waiting = traceSteps(EventTrace("e2", "report.created", "api-1", null, "2026-10-29T03:00:00Z", null, emptyList()))
        assertTrue(waiting.last().hops.isEmpty()); assertTrue("Tolerância a falhas" in waiting.last().concepts)
        val collected = traceSteps(EventTrace("e3", "crossing.updated", null, "bertioga-guaruja", "2026-10-29T03:00:00Z", null, emptyList()))
        assertEquals(listOf("semil" to "col"), collected.first().hops)
        val missed = EventTrace("e4", "report.created", "api-1", null, "2026-10-29T03:00:00Z", "2026-10-29T03:00:01Z", emptyList())
        assertEquals("Entregue por ressincronização", traceSteps(missed, Instant.parse("2026-10-29T03:00:30Z")).last().label)
        assertEquals("Worker → RabbitMQ", traceSteps(missed, Instant.parse("2026-10-29T03:00:02Z")).last().label)
    }
    @Test fun oldOrFutureLocationCacheIsNotUsedAsCurrentPosition() {
        assertTrue(isRecentLocation(60_000))
        assertFalse(isRecentLocation(120_001))
        assertFalse(isRecentLocation(-1))
    }
}
