package ru.vasilev.support.ticket.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.vasilev.support.ticket.entity.TicketStatusHistory;

public interface TicketStatusHistoryRepository extends JpaRepository<TicketStatusHistory, Long> {
    List<TicketStatusHistory> findByTicketIdOrderByIdAsc(UUID ticketId);
}
