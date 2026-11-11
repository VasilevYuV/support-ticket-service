package ru.vasilev.support.ticket.service;

import ru.vasilev.support.ticket.entity.TicketStatus;

public final class TicketTransitionPolicy {
    private TicketTransitionPolicy() {}

    public static boolean isAllowed(TicketStatus from, TicketStatus to) {
        return switch (from) {
            case NEW -> to == TicketStatus.IN_PROGRESS || to == TicketStatus.CANCELLED;
            case IN_PROGRESS -> to == TicketStatus.RESOLVED || to == TicketStatus.CANCELLED;
            case RESOLVED, CANCELLED -> false;
        };
    }
}
