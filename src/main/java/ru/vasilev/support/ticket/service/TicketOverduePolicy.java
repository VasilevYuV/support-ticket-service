package ru.vasilev.support.ticket.service;

import java.time.Instant;
import ru.vasilev.support.ticket.entity.TicketStatus;

public final class TicketOverduePolicy {
    private TicketOverduePolicy() {}

    public static boolean isOverdue(TicketStatus status, Instant dueAt, Instant now) {
        boolean open = status == TicketStatus.NEW || status == TicketStatus.IN_PROGRESS;
        return open && dueAt != null && dueAt.isBefore(now);
    }
}
