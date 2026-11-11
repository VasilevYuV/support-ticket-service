package ru.vasilev.support.ticket.dto;

import ru.vasilev.support.ticket.entity.TicketPriority;
import ru.vasilev.support.ticket.entity.TicketStatus;

public record TicketSearchRequest(
        TicketStatus status,
        TicketPriority priority,
        Long categoryId,
        Boolean overdue,
        int page,
        int size) {
    public long offset() {
        return (long) page * size;
    }
}
