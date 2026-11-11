package ru.vasilev.support.ticket.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import ru.vasilev.support.ticket.entity.TicketStatus
import java.time.Instant

class TicketOverduePolicyTest {
    private val now = Instant.parse("2026-11-11T09:00:00.123456Z")

    @ParameterizedTest
    @EnumSource(TicketStatus::class)
    fun pastDueDateIsOverdueOnlyForOpenStates(status: TicketStatus) {
        val expected = status in setOf(TicketStatus.NEW, TicketStatus.IN_PROGRESS)
        assertThat(TicketOverduePolicy.isOverdue(status, now.minusNanos(1000), now)).isEqualTo(expected)
    }

    @ParameterizedTest
    @EnumSource(TicketStatus::class, names = ["NEW", "IN_PROGRESS"])
    fun exactBoundaryAndFutureAreNotOverdue(status: TicketStatus) {
        assertThat(TicketOverduePolicy.isOverdue(status, now, now)).isFalse()
        assertThat(TicketOverduePolicy.isOverdue(status, now.plusNanos(1000), now)).isFalse()
    }

    @ParameterizedTest
    @EnumSource(TicketStatus::class)
    fun absentDueDateIsNotOverdue(status: TicketStatus) {
        assertThat(TicketOverduePolicy.isOverdue(status, null, now)).isFalse()
    }
}
