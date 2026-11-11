package ru.vasilev.support.report.repository;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.vasilev.support.report.dto.ReportTicket;

@Repository
@NullMarked
public class JpaReportDataSource implements ReportDataSource {
    private final EntityManager entityManager;

    public JpaReportDataSource(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReportTicket> findCreatedBetween(Instant from, Instant to) {
        return entityManager
                .createQuery(
                        """
                        select new ru.vasilev.support.report.dto.ReportTicket(
                            ticket.status, ticket.priority, ticket.createdAt, ticket.dueAt, ticket.closedAt)
                        from Ticket ticket
                        where ticket.createdAt >= :from and ticket.createdAt < :to
                        """,
                        ReportTicket.class)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }
}
