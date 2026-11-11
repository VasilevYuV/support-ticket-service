package ru.vasilev.support.report.dto;

import java.time.Instant;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import ru.vasilev.support.ticket.entity.TicketPriority;
import ru.vasilev.support.ticket.entity.TicketStatus;

@NullMarked
public record ReportTicket(
        TicketStatus status,
        TicketPriority priority,
        Instant createdAt,
        @Nullable Instant dueAt,
        @Nullable Instant closedAt) {}
