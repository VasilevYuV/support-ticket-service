package ru.vasilev.support.ticket.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import ru.vasilev.support.config.ApiTestSupport;

abstract class TicketWorkflowTestSupport extends ApiTestSupport {
    protected UUID createTicket(String status) throws Exception {
        var created =
                send(
                        "POST",
                        "/api/tickets",
                        """
                        {"title":"Заявка","categoryId":%d,"description":"Исходное","priority":"HIGH","dueAt":"2026-11-10T09:00:00Z"}
                        """
                                .formatted(categoryId));
        assertThat(created.status()).isEqualTo(201);
        var id = UUID.fromString(created.body().get("id").stringValue());
        if (status.equals("IN_PROGRESS") || status.equals("RESOLVED")) {
            assertThat(change(id, "IN_PROGRESS").status()).isEqualTo(200);
        }
        if (status.equals("RESOLVED") || status.equals("CANCELLED")) {
            assertThat(change(id, status).status()).isEqualTo(200);
        }
        return id;
    }

    protected ApiResult change(UUID id, String status) throws Exception {
        return send("PATCH", statusPath(id), "{\"status\":\"" + status + "\"}");
    }

    protected String statusPath(UUID id) {
        return "/api/tickets/" + id + "/status";
    }

    protected String historyPath(UUID id) {
        return "/api/tickets/" + id + "/history";
    }

    protected Map<String, Object> snapshot(UUID id) {
        return jdbc.queryForMap("SELECT * FROM tickets WHERE id = ?", id);
    }

    protected List<Map<String, Object>> history(UUID id) {
        return jdbc.queryForList(
                "SELECT * FROM ticket_status_history WHERE ticket_id = ? ORDER BY id", id);
    }
}
