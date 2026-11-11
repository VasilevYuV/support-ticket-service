package ru.vasilev.support.ticket.dto;

import java.time.Instant;
import ru.vasilev.support.ticket.entity.TicketStatus;
import ru.vasilev.support.ticket.entity.TicketStatusHistory;

public record StatusHistoryResponse(
        Long id, TicketStatus fromStatus, TicketStatus toStatus, Instant changedAt) {
    public static StatusHistoryResponse from(TicketStatusHistory entry) {
        return new StatusHistoryResponse(
                entry.getId(), entry.getFromStatus(), entry.getToStatus(), entry.getChangedAt());
    }
}
