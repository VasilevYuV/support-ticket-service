package ru.vasilev.support.ticket.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.vasilev.support.config.ApiTestSupport;
import tools.jackson.databind.JsonNode;

class TicketLifecycleE2ETest extends ApiTestSupport {
    private static final Instant CREATED_AT = Instant.parse("2035-12-31T23:00:00.123456Z");
    private static final Instant STARTED_AT = CREATED_AT.plusSeconds(60);
    private static final Instant CLOSED_AT = CREATED_AT.plusSeconds(3600);
    private static final Instant UPDATED_DUE_AT = CREATED_AT.plusSeconds(600);

    @MockitoBean(name = "fixedClock")
    private Clock scenarioClock;

    private Instant currentTime;

    @BeforeEach
    void resetClock() {
        setTime(CREATED_AT);
    }

    @Override
    protected Instant expectedNow() {
        return currentTime;
    }

    @Test
    @DisplayName("E2E-01: жизненный цикл заявки и отчёт")
    void completesLifecycleAndCalculatesReport() throws Exception {
        var created = createTicket();
        String path = ticketPath(created);
        assertThat(read(path)).isEqualTo(created);
        assertThat(read(path + "/history").isEmpty()).isTrue();

        var updated = replaceDetails(path);
        assertThat(updated.get("description").stringValue()).isEqualTo("Проверено подключение.");
        assertThat(updated.get("priority").stringValue()).isEqualTo("HIGH");
        assertThat(updated.get("dueAt").stringValue()).isEqualTo(UPDATED_DUE_AT.toString());
        assertUnchangedIdentity(updated, created);
        assertThat(read(path)).isEqualTo(updated);
        assertThat(read(path + "/history").isEmpty()).isTrue();

        setTime(STARTED_AT);
        var active = changeStatus(path, "IN_PROGRESS");
        assertThat(active.get("closedAt").isNull()).isTrue();
        assertThat(active.get("overdue").booleanValue()).isFalse();
        assertUnchangedIdentity(active, created);
        var activeHistory = read(path + "/history");
        assertThat(activeHistory.size()).isEqualTo(1);
        assertHistoryEntry(activeHistory.get(0), "NEW", "IN_PROGRESS", STARTED_AT);

        setTime(CLOSED_AT);
        var resolved = changeStatus(path, "RESOLVED");
        assertUnchangedIdentity(resolved, created);
        assertThat(resolved.get("description")).isEqualTo(updated.get("description"));
        assertThat(resolved.get("priority")).isEqualTo(updated.get("priority"));
        assertThat(resolved.get("dueAt")).isEqualTo(updated.get("dueAt"));
        assertThat(resolved.get("closedAt").stringValue()).isEqualTo(CLOSED_AT.toString());
        assertThat(resolved.get("overdue").booleanValue()).isFalse();

        var finalState = snapshot(path);
        assertThat(finalState.ticket()).isEqualTo(resolved);
        assertTerminalViews(finalState, "RESOLVED", "HIGH");
        assertHistoryEntry(finalState.history().get(0), "NEW", "IN_PROGRESS", STARTED_AT);
        assertHistoryEntry(finalState.history().get(1), "IN_PROGRESS", "RESOLVED", CLOSED_AT);
        assertThat(finalState.history().get(1).get("id").longValue())
                .isGreaterThan(finalState.history().get(0).get("id").longValue());

        var rows = finalState.database().get("tickets");
        assertThat(rows).hasSize(1);
        var saved = rows.getFirst();
        assertThat(saved)
                .containsEntry("status", "RESOLVED")
                .containsEntry("priority", "HIGH")
                .containsEntry("description", "Проверено подключение.");
        assertThat(((Timestamp) saved.get("created_at")).toInstant()).isEqualTo(CREATED_AT);
        assertThat(((Timestamp) saved.get("due_at")).toInstant()).isEqualTo(UPDATED_DUE_AT);
        assertThat(((Timestamp) saved.get("closed_at")).toInstant()).isEqualTo(CLOSED_AT);
        assertThat(finalState.database().get("history")).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESOLVED", "CANCELLED"})
    @DisplayName("E2E-02: запрет изменения терминальной заявки")
    void rejectsTerminalChangesWithoutChangingAnyView(String terminalStatus) throws Exception {
        var created = createTicket();
        String path = ticketPath(created);
        setTime(STARTED_AT);
        changeStatus(path, "IN_PROGRESS");
        setTime(CLOSED_AT);
        changeStatus(path, terminalStatus);
        var before = snapshot(path);
        assertTerminalViews(before, terminalStatus, "MEDIUM");
        assertThat(before.ticket().get("closedAt").stringValue()).isEqualTo(CLOSED_AT.toString());
        assertHistoryEntry(before.history().get(0), "NEW", "IN_PROGRESS", STARTED_AT);
        assertHistoryEntry(before.history().get(1), "IN_PROGRESS", terminalStatus, CLOSED_AT);
        assertThat(before.database().get("tickets")).hasSize(1);
        assertThat(before.database().get("history")).hasSize(2);

        var reopen = send("PATCH", path + "/status", "{\"status\":\"IN_PROGRESS\"}");
        assertError(reopen, 409, "STATE_CONFLICT", path + "/status");
        assertThat(snapshot(path)).isEqualTo(before);

        var edit = send("PUT", path + "/details", replacementBody());
        assertError(edit, 409, "STATE_CONFLICT", path + "/details");
        assertThat(snapshot(path)).isEqualTo(before);
    }

    private JsonNode createTicket() throws Exception {
        var categories = read("/api/categories");
        assertThat(categories.size()).isEqualTo(4);
        var selected = categories.get(0);
        var body = json.createObjectNode();
        body.put("title", "Не работает принтер");
        body.put("categoryId", selected.get("id").longValue());
        body.put("dueAt", CREATED_AT.plusSeconds(300).toString());
        var response = send("POST", "/api/tickets", json.writeValueAsString(body));
        assertThat(response.status()).isEqualTo(201);
        var ticket = response.body();
        assertThat(response.location()).isEqualTo(ticketPath(ticket));
        assertThat(ticket.get("category")).isEqualTo(selected);
        assertThat(ticket.get("status").stringValue()).isEqualTo("NEW");
        assertThat(ticket.get("priority").stringValue()).isEqualTo("MEDIUM");
        assertThat(ticket.get("description").stringValue()).isEmpty();
        assertThat(ticket.get("createdAt").stringValue()).isEqualTo(CREATED_AT.toString());
        assertThat(ticket.get("closedAt").isNull()).isTrue();
        return ticket;
    }

    private JsonNode replaceDetails(String path) throws Exception {
        var response = send("PUT", path + "/details", replacementBody());
        assertThat(response.status()).isEqualTo(200);
        return response.body();
    }

    private String replacementBody() {
        return """
        {"description":"Проверено подключение.","priority":"HIGH","dueAt":"%s"}
        """
                .formatted(UPDATED_DUE_AT);
    }

    private JsonNode changeStatus(String path, String status) throws Exception {
        var response = send("PATCH", path + "/status", "{\"status\":\"" + status + "\"}");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("status").stringValue()).isEqualTo(status);
        return response.body();
    }

    private JsonNode read(String path) throws Exception {
        var response = send("GET", path, null);
        assertThat(response.status()).isEqualTo(200);
        return response.body();
    }

    private String ticketPath(JsonNode ticket) {
        return "/api/tickets/" + ticket.get("id").stringValue();
    }

    private void setTime(Instant time) {
        currentTime = time;
        when(scenarioClock.instant()).thenReturn(time.plusNanos(789));
    }

    private void assertUnchangedIdentity(JsonNode actual, JsonNode created) {
        for (String field : List.of("id", "title", "category", "createdAt")) {
            assertThat(actual.get(field)).as(field).isEqualTo(created.get(field));
        }
    }

    private void assertHistoryEntry(JsonNode entry, String from, String to, Instant changedAt) {
        assertThat(entry.get("fromStatus").stringValue()).isEqualTo(from);
        assertThat(entry.get("toStatus").stringValue()).isEqualTo(to);
        assertThat(entry.get("changedAt").stringValue()).isEqualTo(changedAt.toString());
    }

    private void assertTerminalViews(Snapshot state, String status, String priority) {
        assertThat(state.ticket().get("status").stringValue()).isEqualTo(status);
        assertThat(state.history().size()).isEqualTo(2);
        assertThat(state.list().get("content").size()).isEqualTo(1);
        assertThat(state.list().get("content").get(0)).isEqualTo(state.ticket());
        assertThat(state.list().get("totalElements").longValue()).isEqualTo(1);
        assertThat(state.list().get("totalPages").longValue()).isEqualTo(1);
        assertThat(state.report().get("totalCreated").longValue()).isEqualTo(1);
        assertThat(state.report().get("openOverdue").longValue()).isZero();
        assertDistribution(
                state.report().get("byStatus"),
                List.of("NEW", "IN_PROGRESS", "RESOLVED", "CANCELLED"),
                status);
        assertDistribution(
                state.report().get("byPriority"), List.of("LOW", "MEDIUM", "HIGH"), priority);
        if (status.equals("RESOLVED")) {
            assertThat(state.report().get("averageResolutionSeconds").decimalValue())
                    .isEqualByComparingTo("3600");
        } else {
            assertThat(state.report().get("averageResolutionSeconds").isNull()).isTrue();
        }
    }

    private void assertDistribution(JsonNode counts, List<String> groups, String selected) {
        assertThat(counts.size()).isEqualTo(groups.size());
        for (String group : groups) {
            assertThat(counts.get(group).longValue())
                    .as(group)
                    .isEqualTo(group.equals(selected) ? 1 : 0);
        }
    }

    private Snapshot snapshot(String path) throws Exception {
        var database =
                Map.of(
                        "categories", jdbc.queryForList("SELECT * FROM categories ORDER BY id"),
                        "tickets", jdbc.queryForList("SELECT * FROM tickets ORDER BY id"),
                        "history",
                                jdbc.queryForList(
                                        "SELECT * FROM ticket_status_history ORDER BY id"));
        return new Snapshot(
                database,
                read(path),
                read(path + "/history"),
                read("/api/tickets?page=0&size=10"),
                read(
                        "/api/reports/summary?from="
                                + CREATED_AT
                                + "&to="
                                + CREATED_AT.plusSeconds(1)));
    }

    private record Snapshot(
            Map<String, List<Map<String, Object>>> database,
            JsonNode ticket,
            JsonNode history,
            JsonNode list,
            JsonNode report) {}
}
