package ru.vasilev.support.ticket.dto;

import java.time.Instant;
import ru.vasilev.support.ticket.entity.TicketPriority;

public record CreateTicketRequest(
        String title,
        Long categoryId,
        String description,
        TicketPriority priority,
        Instant dueAt) {}
