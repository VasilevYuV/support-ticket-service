package ru.vasilev.support.ticket.mapper;

import java.time.Instant;
import org.springframework.stereotype.Component;
import ru.vasilev.support.category.dto.CategoryResponse;
import ru.vasilev.support.ticket.dto.TicketResponse;
import ru.vasilev.support.ticket.entity.Ticket;
import ru.vasilev.support.ticket.service.TicketOverduePolicy;

@Component
public class TicketMapper {
    public TicketResponse toResponse(Ticket ticket, Instant now) {
        return new TicketResponse(
                ticket.getId(),
                ticket.getTitle(),
                ticket.getDescription(),
                CategoryResponse.from(ticket.getCategory()),
                ticket.getPriority(),
                ticket.getStatus(),
                ticket.getCreatedAt(),
                ticket.getDueAt(),
                ticket.getClosedAt(),
                TicketOverduePolicy.isOverdue(ticket.getStatus(), ticket.getDueAt(), now));
    }
}
