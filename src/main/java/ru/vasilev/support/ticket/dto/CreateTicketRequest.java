package ru.vasilev.support.ticket.dto;

import java.time.Instant;
import ru.vasilev.support.ticket.entity.TicketPriority;
import ru.vasilev.support.ticket.validation.CreateTicketRequestDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = CreateTicketRequestDeserializer.class)
public record CreateTicketRequest(
        String title,
        Long categoryId,
        String description,
        TicketPriority priority,
        Instant dueAt) {}
