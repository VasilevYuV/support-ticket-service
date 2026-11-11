package ru.vasilev.support.ticket.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.vasilev.support.ticket.entity.Ticket;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ticket from Ticket ticket where ticket.id = :id")
    Optional<Ticket> findByIdForUpdate(@Param("id") UUID id);
}
