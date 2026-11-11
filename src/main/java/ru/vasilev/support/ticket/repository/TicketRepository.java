package ru.vasilev.support.ticket.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.vasilev.support.ticket.entity.Ticket;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {}
