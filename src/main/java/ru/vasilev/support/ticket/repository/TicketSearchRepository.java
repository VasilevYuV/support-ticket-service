package ru.vasilev.support.ticket.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;
import ru.vasilev.support.ticket.dto.TicketSearchRequest;
import ru.vasilev.support.ticket.entity.Ticket;

@Repository
public class TicketSearchRepository {
    private final EntityManager entityManager;

    public TicketSearchRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public SearchResult search(TicketSearchRequest request, Instant now) {
        var conditions = new ArrayList<String>();
        var parameters = new LinkedHashMap<String, Object>();
        if (request.status() != null) {
            conditions.add("t.status = :status");
            parameters.put("status", request.status().name());
        }
        if (request.priority() != null) {
            conditions.add("t.priority = :priority");
            parameters.put("priority", request.priority().name());
        }
        if (request.categoryId() != null) {
            conditions.add("t.category_id = :categoryId");
            parameters.put("categoryId", request.categoryId());
        }
        if (request.overdue() != null) {
            String overdue =
                    "(t.status IN ('NEW', 'IN_PROGRESS') AND t.due_at IS NOT NULL AND t.due_at < :now)";
            conditions.add(request.overdue() ? overdue : "NOT " + overdue);
            parameters.put("now", now);
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        var countQuery = entityManager.createNativeQuery("SELECT count(*) FROM tickets t" + where);
        bind(countQuery, parameters);
        long total = ((Number) countQuery.getSingleResult()).longValue();
        if (request.offset() >= total) {
            return new SearchResult(List.of(), total);
        }

        // SQL OFFSET принимает long, включая смещения за пределами диапазона int.
        var query =
                entityManager.createNativeQuery(
                        "SELECT t.* FROM tickets t"
                                + where
                                + " ORDER BY t.created_at DESC, t.id DESC LIMIT :size OFFSET :offset",
                        Ticket.class);
        bind(query, parameters);
        query.setParameter("size", request.size());
        query.setParameter("offset", request.offset());
        List<?> rows = query.getResultList();
        return new SearchResult(rows.stream().map(Ticket.class::cast).toList(), total);
    }

    private void bind(Query query, Map<String, Object> parameters) {
        parameters.forEach(query::setParameter);
    }

    public record SearchResult(List<Ticket> content, long totalElements) {}
}
