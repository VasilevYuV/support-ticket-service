package ru.vasilev.support.report.service

import ru.vasilev.support.common.error.ApiException
import ru.vasilev.support.report.dto.ReportTicket
import ru.vasilev.support.report.dto.SummaryReport
import ru.vasilev.support.report.repository.ReportDataSource
import ru.vasilev.support.ticket.entity.TicketPriority
import ru.vasilev.support.ticket.entity.TicketStatus
import ru.vasilev.support.ticket.service.TicketOverduePolicy
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

class SummaryReportService(
    private val dataSource: ReportDataSource,
    private val clock: Clock,
) {
    fun generate(
        from: Instant,
        to: Instant,
    ): SummaryReport {
        if (!from.isBefore(to)) {
            throw ApiException.validation("period", "Начало периода должно быть раньше окончания.")
        }
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val tickets = dataSource.findCreatedBetween(from, to)
        val statusCounts = tickets.groupingBy { it.status() }.eachCount()
        val priorityCounts = tickets.groupingBy { it.priority() }.eachCount()
        val byStatus = TicketStatus.entries.associateWith { (statusCounts[it] ?: 0).toLong() }
        val byPriority = TicketPriority.entries.associateWith { (priorityCounts[it] ?: 0).toLong() }
        val overdue = tickets.count { TicketOverduePolicy.isOverdue(it.status(), it.dueAt(), now) }
        return SummaryReport(
            from = from,
            to = to,
            totalCreated = tickets.size.toLong(),
            byStatus = java.util.Map.copyOf(byStatus),
            byPriority = java.util.Map.copyOf(byPriority),
            openOverdue = overdue.toLong(),
            averageResolutionSeconds = averageResolution(tickets),
        )
    }

    private fun averageResolution(tickets: List<ReportTicket>): BigDecimal? {
        val resolved = tickets.filter { it.status() == TicketStatus.RESOLVED }
        if (resolved.isEmpty()) return null
        val totalSeconds =
            resolved.fold(BigDecimal.ZERO) { total, ticket ->
                val closedAt: Instant = checkNotNull(ticket.closedAt()) { "У решённой заявки отсутствует время закрытия." }
                val duration = Duration.between(ticket.createdAt(), closedAt)
                check(!duration.isNegative) { "Время закрытия заявки раньше времени создания." }
                // Секунды и дробная часть складываются точно; округление выполняется только после деления.
                total + duration.seconds.toBigDecimal() + BigDecimal.valueOf(duration.nano.toLong(), 9)
            }
        return totalSeconds.divide(resolved.size.toBigDecimal(), 6, RoundingMode.HALF_UP)
    }
}
