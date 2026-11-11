package ru.vasilev.support.report.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import ru.vasilev.support.common.error.ApiException
import ru.vasilev.support.report.dto.ReportTicket
import ru.vasilev.support.report.repository.ReportDataSource
import ru.vasilev.support.ticket.entity.TicketPriority
import ru.vasilev.support.ticket.entity.TicketStatus
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SummaryReportServiceTest {
    private val from = Instant.parse("2026-11-01T00:00:00Z")
    private val to = Instant.parse("2026-11-02T00:00:00Z")
    private val now = Instant.parse("2026-11-03T00:00:00Z")

    @Test
    fun calculatesControlDatasetWithOneSourceReadAndOneClockRead() {
        val tickets =
            listOf(
                ticket(TicketStatus.NEW, TicketPriority.HIGH, from, now.minusNanos(1000)),
                ticket(TicketStatus.IN_PROGRESS, TicketPriority.MEDIUM, from.plusSeconds(3600), now),
                ticket(
                    TicketStatus.RESOLVED,
                    TicketPriority.LOW,
                    from.plusSeconds(7200),
                    from.plusSeconds(10800),
                    from.plusSeconds(10800),
                ),
                ticket(TicketStatus.RESOLVED, TicketPriority.HIGH, from.plusSeconds(82800), null, to.plusSeconds(7200)),
                ticket(
                    TicketStatus.CANCELLED,
                    TicketPriority.LOW,
                    from.plusSeconds(10800),
                    from.plusSeconds(14400),
                    from.plusSeconds(14400),
                ),
                ticket(TicketStatus.NEW, TicketPriority.MEDIUM, from.plusSeconds(14400)),
            )
        val source = mock(ReportDataSource::class.java)
        val clock = mock(Clock::class.java)
        `when`(source.findCreatedBetween(from, to)).thenReturn(tickets)
        `when`(clock.instant()).thenReturn(now.plusNanos(789), now.plusSeconds(1))

        val report = SummaryReportService(source, clock).generate(from, to)

        assertThat(report.from).isEqualTo(from)
        assertThat(report.to).isEqualTo(to)
        assertThat(report.totalCreated).isEqualTo(6)
        assertThat(report.byStatus).isEqualTo(
            mapOf(
                TicketStatus.NEW to 2L,
                TicketStatus.IN_PROGRESS to 1L,
                TicketStatus.RESOLVED to 2L,
                TicketStatus.CANCELLED to 1L,
            ),
        )
        assertThat(report.byPriority).isEqualTo(mapOf(TicketPriority.LOW to 2L, TicketPriority.MEDIUM to 2L, TicketPriority.HIGH to 2L))
        assertThat(report.byStatus.values.sum()).isEqualTo(report.totalCreated)
        assertThat(report.byPriority.values.sum()).isEqualTo(report.totalCreated)
        assertThat(report.openOverdue).isEqualTo(1)
        assertThat(report.averageResolutionSeconds).isEqualByComparingTo("7200.000000")
        verify(source, times(1)).findCreatedBetween(from, to)
        verify(clock, times(1)).instant()
        verifyNoMoreInteractions(source, clock)
    }

    @Test
    fun emptySelectionContainsEveryGroupAndNullAverage() {
        val report = service(emptyList()).generate(from, to)
        assertThat(report.totalCreated).isZero()
        assertThat(report.openOverdue).isZero()
        assertThat(report.byStatus.keys).containsExactlyInAnyOrder(*TicketStatus.entries.toTypedArray())
        assertThat(report.byPriority.keys).containsExactlyInAnyOrder(*TicketPriority.entries.toTypedArray())
        assertThat(report.byStatus.values).containsOnly(0L)
        assertThat(report.byPriority.values).containsOnly(0L)
        assertThat(report.averageResolutionSeconds).isNull()
    }

    @Test
    fun cancelledAndOpenTicketsDoNotContributeToAverage() {
        val report =
            service(
                listOf(
                    ticket(TicketStatus.NEW, TicketPriority.LOW, dueAt = now.minusSeconds(1)),
                    ticket(TicketStatus.IN_PROGRESS, TicketPriority.LOW),
                    ticket(TicketStatus.CANCELLED, TicketPriority.HIGH, dueAt = now.minusSeconds(1), closedAt = to),
                ),
            ).generate(from, to)
        assertThat(report.totalCreated).isEqualTo(3)
        assertThat(report.openOverdue).isEqualTo(1)
        assertThat(report.byStatus[TicketStatus.RESOLVED]).isZero()
        assertThat(report.byPriority[TicketPriority.MEDIUM]).isZero()
        assertThat(report.averageResolutionSeconds).isNull()
    }

    @ParameterizedTest
    @CsvSource("0,0,1000,0.000000", "0,1000,1000,0.000001", "0,1000,2000,0.000001", "1000000000,2000000000,3000000000,2.000000")
    fun roundsAverageOnlyAfterExactSummation(
        firstNanos: Long,
        secondNanos: Long,
        thirdNanos: Long,
        expected: String,
    ) {
        val tickets =
            listOf(firstNanos, secondNanos, thirdNanos).map {
                ticket(TicketStatus.RESOLVED, closedAt = from.plusNanos(it))
            }
        assertThat(service(tickets).generate(from, to).averageResolutionSeconds).isEqualTo(BigDecimal(expected))
    }

    @Test
    fun roundsHalfMicrosecondUp() {
        val tickets = listOf(ticket(TicketStatus.RESOLVED, closedAt = from), ticket(TicketStatus.RESOLVED, closedAt = from.plusNanos(1000)))
        assertThat(service(tickets).generate(from, to).averageResolutionSeconds).isEqualTo(BigDecimal("0.000001"))
    }

    @Test
    fun keepsSubsecondPrecisionAcrossSecondBoundary() {
        val createdAt = from.plusNanos(999999000)
        val closedAt = from.plusSeconds(1).plusNanos(1000)
        val report = service(listOf(ticket(TicketStatus.RESOLVED, createdAt = createdAt, closedAt = closedAt))).generate(from, to)
        assertThat(report.averageResolutionSeconds).isEqualTo(BigDecimal("0.000002"))
    }

    @Test
    fun handlesLongDurationWithoutConvertingToLongNanoseconds() {
        val createdAt = Instant.parse("0001-01-01T00:00:00Z")
        val closedAt = Instant.parse("9999-12-31T23:59:59.999999Z")
        val report =
            service(listOf(ticket(TicketStatus.RESOLVED, createdAt = createdAt, closedAt = closedAt)))
                .generate(createdAt, createdAt.plusSeconds(1))
        assertThat(report.averageResolutionSeconds).isEqualTo(BigDecimal("315537897599.999999"))
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun invalidPeriodDoesNotReadSourceOrClock(offset: Long) {
        val source = mock(ReportDataSource::class.java)
        val clock = mock(Clock::class.java)
        assertThatThrownBy { SummaryReportService(source, clock).generate(from, from.plusSeconds(offset)) }
            .isInstanceOfSatisfying(ApiException::class.java) { error ->
                assertThat(error.status.value()).isEqualTo(400)
                assertThat(error.violations.first().field()).isEqualTo("period")
            }
        verifyNoInteractions(source, clock)
    }

    @Test
    fun invalidResolvedRowIsNotSilentlyExcludedFromAverage() {
        for (closedAt in listOf(null, from.minusSeconds(1))) {
            assertThatThrownBy { service(listOf(ticket(TicketStatus.RESOLVED, closedAt = closedAt))).generate(from, to) }
                .isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun resultDoesNotDependOnInputOrderOrClockZone() {
        val tickets =
            listOf(
                ticket(TicketStatus.NEW, dueAt = now.minusNanos(1000)),
                ticket(TicketStatus.RESOLVED, closedAt = to.plusSeconds(10)),
                ticket(TicketStatus.RESOLVED, closedAt = from.plusNanos(1000)),
            )
        val source = ReportDataSource { _, _ -> tickets.reversed() }
        val otherZone = SummaryReportService(source, Clock.fixed(now, ZoneOffset.ofHours(3)))
        assertThat(otherZone.generate(from, to)).isEqualTo(service(tickets).generate(from, to))
    }

    private fun service(tickets: List<ReportTicket>): SummaryReportService =
        SummaryReportService(ReportDataSource { _, _ -> tickets }, Clock.fixed(now, ZoneOffset.UTC))

    private fun ticket(
        status: TicketStatus,
        priority: TicketPriority = TicketPriority.MEDIUM,
        createdAt: Instant = from,
        dueAt: Instant? = null,
        closedAt: Instant? = null,
    ): ReportTicket = ReportTicket(status, priority, createdAt, dueAt, closedAt)
}
