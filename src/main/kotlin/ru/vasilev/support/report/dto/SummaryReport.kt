package ru.vasilev.support.report.dto

import ru.vasilev.support.ticket.entity.TicketPriority
import ru.vasilev.support.ticket.entity.TicketStatus
import java.math.BigDecimal
import java.time.Instant

data class SummaryReport(
    val from: Instant,
    val to: Instant,
    val totalCreated: Long,
    val byStatus: Map<TicketStatus, Long>,
    val byPriority: Map<TicketPriority, Long>,
    val openOverdue: Long,
    val averageResolutionSeconds: BigDecimal?,
)
