package ru.vasilev.support.ticket.dto;

import java.time.Instant;
import java.util.UUID;
import ru.vasilev.support.category.dto.CategoryResponse;
import ru.vasilev.support.ticket.entity.TicketPriority;
import ru.vasilev.support.ticket.entity.TicketStatus;

public record TicketResponse(
        UUID id,
        String title,
        String description,
        CategoryResponse category,
        TicketPriority priority,
        TicketStatus status,
        Instant createdAt,
        Instant dueAt,
        Instant closedAt,
        boolean overdue) {}
