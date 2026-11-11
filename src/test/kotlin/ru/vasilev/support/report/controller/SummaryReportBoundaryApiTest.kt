package ru.vasilev.support.report.controller

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.`when`
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import ru.vasilev.support.config.ApiTestSupport
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@TestPropertySource(
    properties = [
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=OFF",
    ],
)
class SummaryReportBoundaryApiTest : ApiTestSupport() {
    @MockitoBean(name = "fixedClock")
    private lateinit var reportClock: Clock

    @ParameterizedTest
    @CsvSource(
        "0;0;0, 0.000000",
        "0;0;1, 0.000000",
        "0;1;1, 0.000001",
        "0;1, 0.000001",
        "0;1;2, 0.000001",
    )
    fun preservesMicrosecondsFromPostgresThroughJson(
        durations: String,
        expected: String,
    ) {
        val createdAt = Instant.parse("2026-11-01T00:00:00.999999Z")
        val microseconds = durations.split(';').map(String::toLong)
        microseconds.forEachIndexed { index, duration ->
            addResolved(index + 1, createdAt, createdAt.plusNanos(duration * 1000))
        }
        setTime(createdAt.plusSeconds(10))

        val report = report(createdAt, createdAt.plusSeconds(1))

        assertThat(report["totalCreated"].longValue()).isEqualTo(microseconds.size.toLong())
        assertThat(report["byStatus"]["RESOLVED"].longValue()).isEqualTo(microseconds.size.toLong())
        assertThat(report["averageResolutionSeconds"].isNumber).isTrue()
        assertThat(report["averageResolutionSeconds"].decimalValue()).isEqualByComparingTo(expected)
    }

    @Test
    fun preservesLargeDurationWithoutOverflowOrDecimalLoss() {
        val createdAt = Instant.parse("0001-01-01T00:00:00Z")
        val closedAt = Instant.parse("9999-12-31T23:59:59.999999Z")
        addResolved(1, createdAt, closedAt)
        setTime(closedAt)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM tickets WHERE created_at >= ? AND created_at < ?",
                Long::class.java,
                createdAt.atOffset(ZoneOffset.UTC),
                closedAt.atOffset(ZoneOffset.UTC),
            ),
        ).isEqualTo(1)

        val report = report(createdAt, closedAt)

        assertThat(report["totalCreated"].longValue()).isEqualTo(1)
        assertThat(report["averageResolutionSeconds"].decimalValue())
            .isEqualByComparingTo("315537897599.999999")
    }

    @ParameterizedTest
    @ValueSource(strings = ["2000-02-29T12:00:00Z", "2035-12-31T23:59:59Z"])
    fun recalculatesOverdueAtEachClockBoundaryAndAfterCancellation(date: String) {
        val createdAt = Instant.parse(date)
        val dueAt = createdAt.plusSeconds(1)
        setTime(createdAt)
        val id = createTicket(dueAt)
        val to = createdAt.plusSeconds(1)

        assertThat(report(createdAt, to)["openOverdue"].longValue()).isZero()
        setTime(dueAt.plusNanos(789))
        assertThat(report(createdAt, to)["openOverdue"].longValue()).isZero()
        setTime(dueAt.plusNanos(1000))
        assertThat(report(createdAt, to)["openOverdue"].longValue()).isEqualTo(1)

        changeStatus(id, "CANCELLED")
        val cancelled = report(createdAt, to)
        assertThat(cancelled["openOverdue"].longValue()).isZero()
        assertThat(cancelled["byStatus"]["CANCELLED"].longValue()).isEqualTo(1)
        assertThat(cancelled["averageResolutionSeconds"].isNull).isTrue()
    }

    @Test
    fun usesCurrentPriorityAndStatusWhenTicketClosesOutsideCreationPeriod() {
        val createdAt = Instant.parse("2026-11-01T00:00:00Z")
        val to = createdAt.plusSeconds(1)
        setTime(createdAt)
        val id = createTicket(to)
        setTime(createdAt.plusSeconds(3600))
        val initial = report(createdAt, to)
        assertThat(initial["byPriority"]["HIGH"].longValue()).isEqualTo(1)
        assertThat(initial["byStatus"]["NEW"].longValue()).isEqualTo(1)
        assertThat(initial["openOverdue"].longValue()).isEqualTo(1)

        val details = """{"description":"","priority":"LOW","dueAt":"$to"}"""
        assertThat(send("PUT", "/api/tickets/$id/details", details).status()).isEqualTo(200)
        changeStatus(id, "IN_PROGRESS")
        val active = report(createdAt, to)
        assertThat(active["byPriority"]["LOW"].longValue()).isEqualTo(1)
        assertThat(active["byPriority"]["HIGH"].longValue()).isZero()
        assertThat(active["byStatus"]["IN_PROGRESS"].longValue()).isEqualTo(1)
        assertThat(active["averageResolutionSeconds"].isNull).isTrue()

        changeStatus(id, "RESOLVED")
        val resolved = report(createdAt, to)
        assertThat(resolved["totalCreated"].longValue()).isEqualTo(1)
        assertThat(resolved["byStatus"]["RESOLVED"].longValue()).isEqualTo(1)
        assertThat(resolved["byStatus"]["IN_PROGRESS"].longValue()).isZero()
        assertThat(resolved["openOverdue"].longValue()).isZero()
        assertThat(resolved["averageResolutionSeconds"].decimalValue()).isEqualByComparingTo("3600")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "0001-01-01T00:00:00Z",
            "1500-02-28T23:59:59Z",
            "1582-10-04T23:59:59Z",
            "1582-10-15T00:00:00Z",
            "9999-12-31T23:59:58Z",
        ],
    )
    fun preservesCalendarDateInDatabaseAndReport(date: String) {
        val createdAt = Instant.parse(date)
        val dueAt = createdAt.plusSeconds(1)
        setTime(createdAt)
        val id = createTicket(dueAt)
        val savedAt =
            jdbc.queryForObject(
                "SELECT created_at FROM tickets WHERE id = ?",
                OffsetDateTime::class.java,
                UUID.fromString(id),
            )
        assertThat(savedAt?.toInstant()).isEqualTo(createdAt)
        val ticket = send("GET", "/api/tickets/$id", null)
        assertThat(ticket.status()).isEqualTo(200)
        assertThat(ticket.body()["createdAt"].stringValue()).isEqualTo(date)
        assertThat(ticket.body()["dueAt"].stringValue()).isEqualTo(dueAt.toString())
        assertThat(report(createdAt, dueAt)["totalCreated"].longValue()).isEqualTo(1)
    }

    private fun setTime(now: Instant) {
        `when`(reportClock.instant()).thenReturn(now)
    }

    private fun report(
        from: Instant,
        to: Instant,
    ): JsonNode {
        val response = send("GET", "/api/reports/summary?from=$from&to=$to", null)
        assertThat(response.status()).isEqualTo(200)
        return response.body()
    }

    private fun createTicket(dueAt: Instant): String {
        val body =
            """{"title":"Проверка отчёта","categoryId":$categoryId,"priority":"HIGH","dueAt":"$dueAt"}"""
        val response = send("POST", "/api/tickets", body)
        assertThat(response.status()).isEqualTo(201)
        return response.body()["id"].stringValue()
    }

    private fun changeStatus(
        id: String,
        status: String,
    ) {
        val response = send("PATCH", "/api/tickets/$id/status", """{"status":"$status"}""")
        assertThat(response.status()).isEqualTo(200)
    }

    private fun addResolved(
        number: Int,
        createdAt: Instant,
        closedAt: Instant,
    ) {
        val id = UUID(0, number.toLong())
        jdbc.update(
            """
            INSERT INTO tickets (id, title, category_id, status, priority, created_at, closed_at)
            VALUES (?, ?, ?, 'RESOLVED', 'LOW', ?, ?)
            """.trimIndent(),
            id,
            "Заявка $number",
            categoryId,
            createdAt.atOffset(ZoneOffset.UTC),
            closedAt.atOffset(ZoneOffset.UTC),
        )
        jdbc.update(
            """
            INSERT INTO ticket_status_history (ticket_id, from_status, to_status, changed_at)
            VALUES (?, 'NEW', 'IN_PROGRESS', ?), (?, 'IN_PROGRESS', 'RESOLVED', ?)
            """.trimIndent(),
            id,
            createdAt.atOffset(ZoneOffset.UTC),
            id,
            closedAt.atOffset(ZoneOffset.UTC),
        )
    }
}
