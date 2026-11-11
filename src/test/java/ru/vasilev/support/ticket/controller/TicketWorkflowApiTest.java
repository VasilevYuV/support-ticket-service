package ru.vasilev.support.ticket.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TicketWorkflowApiTest extends TicketWorkflowTestSupport {
    @ParameterizedTest
    @CsvSource({
        "NEW,IN_PROGRESS",
        "NEW,CANCELLED",
        "IN_PROGRESS,RESOLVED",
        "IN_PROGRESS,CANCELLED"
    })
    void persistsAllowedTransitionAndOrderedHistory(String from, String to) throws Exception {
        var id = createTicket(from);
        var before = snapshot(id);
        var oldHistory = history(id);
        var response = change(id, to);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("status").stringValue()).isEqualTo(to);
        boolean terminal = !to.equals("IN_PROGRESS");
        assertThat(response.body().get("overdue").booleanValue()).isEqualTo(!terminal);
        if (terminal) {
            assertThat(response.body().get("closedAt").stringValue()).isEqualTo(NOW.toString());
        } else {
            assertThat(response.body().get("closedAt").isNull()).isTrue();
        }
        var saved = snapshot(id);
        assertThat(saved).containsEntry("status", to);
        for (String field :
                new String[] {
                    "id", "title", "description", "category_id", "priority", "created_at", "due_at"
                }) {
            assertThat(saved.get(field)).as(field).isEqualTo(before.get(field));
        }
        var entries = history(id);
        assertThat(entries).hasSize(oldHistory.size() + 1);
        assertThat(entries.subList(0, oldHistory.size())).isEqualTo(oldHistory);
        var latest = entries.getLast();
        assertThat(latest).containsEntry("from_status", from).containsEntry("to_status", to);
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT changed_at FROM ticket_status_history WHERE id = ?",
                                        OffsetDateTime.class,
                                        latest.get("id"))
                                .toInstant())
                .isEqualTo(NOW);
        assertThat(saved.get("closed_at")).isEqualTo(terminal ? latest.get("changed_at") : null);
        var readHistory = send("GET", historyPath(id), null);
        assertThat(readHistory.status()).isEqualTo(200);
        assertThat(readHistory.body().size()).isEqualTo(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            var entry = readHistory.body().get(index);
            assertThat(entry.size()).isEqualTo(4);
            assertThat(entry.get("id").longValue()).isEqualTo(entries.get(index).get("id"));
            assertThat(entry.get("fromStatus").stringValue())
                    .isEqualTo(entries.get(index).get("from_status"));
            assertThat(entry.get("toStatus").stringValue())
                    .isEqualTo(entries.get(index).get("to_status"));
            assertThat(entry.get("changedAt").stringValue()).isEqualTo(NOW.toString());
        }
        assertThat(send("GET", "/api/tickets/" + id, null).body()).isEqualTo(response.body());
        assertThat(snapshot(id)).isEqualTo(saved);
        assertThat(history(id)).isEqualTo(entries);
    }

    @ParameterizedTest
    @CsvSource({
        "NEW,NEW",
        "NEW,RESOLVED",
        "IN_PROGRESS,NEW",
        "IN_PROGRESS,IN_PROGRESS",
        "RESOLVED,NEW",
        "RESOLVED,IN_PROGRESS",
        "RESOLVED,RESOLVED",
        "RESOLVED,CANCELLED",
        "CANCELLED,NEW",
        "CANCELLED,IN_PROGRESS",
        "CANCELLED,RESOLVED",
        "CANCELLED,CANCELLED"
    })
    void rejectsEveryForbiddenPairWithoutChanges(String from, String to) throws Exception {
        var id = createTicket(from);
        var before = snapshot(id);
        var entries = history(id);
        assertError(change(id, to), 409, "STATE_CONFLICT", statusPath(id));
        assertThat(snapshot(id)).isEqualTo(before);
        assertThat(history(id)).isEqualTo(entries);
    }

    @Test
    void returnsEmptyHistoryAndKeepsTicketsSeparate() throws Exception {
        var empty = createTicket("NEW");
        var other = createTicket("RESOLVED");
        var response = send("GET", historyPath(empty), null);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().isArray()).isTrue();
        assertThat(response.body().size()).isZero();
        assertThat(send("GET", historyPath(other), null).body().size()).isEqualTo(2);
        assertThat(history(empty)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "null",
                "[]",
                "",
                "{",
                "{\"status\":null}",
                "{\"status\":1}",
                "{\"status\":true}",
                "{\"status\":[]}",
                "{\"status\":\"\"}",
                "{\"status\":\"resolved\"}",
                "{\"status\":\"CLOSED\"}",
                "{\"status\":\"IN_PROGRESS\",\"extra\":null}",
                "{\"status\":\"IN_PROGRESS\",\"closedAt\":null}",
                "{\"status\":\"IN_PROGRESS\",\"status\":\"CANCELLED\"}",
                "{\"status\":\"IN_PROGRESS\"}{}"
            })
    void rejectsMalformedBodyBeforeLookup(String body) throws Exception {
        var id = createTicket("NEW");
        var before = snapshot(id);
        for (var target :
                new UUID[] {id, UUID.fromString("00000000-0000-0000-0000-000000000099")}) {
            var response = send("PATCH", statusPath(target), body);
            assertThat(response.body().get("code").stringValue())
                    .isIn("INVALID_REQUEST", "VALIDATION_ERROR");
            assertError(
                    response, 400, response.body().get("code").stringValue(), statusPath(target));
        }
        assertThat(snapshot(id)).isEqualTo(before);
        assertThat(history(id)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "PATCH,status,1-1-1-1-1,400,INVALID_REQUEST",
        "GET,history,1-1-1-1-1,400,INVALID_REQUEST",
        "PATCH,status,00000000-0000-0000-0000-000000000099,404,TICKET_NOT_FOUND",
        "GET,history,00000000-0000-0000-0000-000000000099,404,TICKET_NOT_FOUND"
    })
    void distinguishesInvalidAndMissingIds(
            String method, String suffix, String id, int status, String code) throws Exception {
        var path = "/api/tickets/" + id + "/" + suffix;
        assertError(
                send(method, path, method.equals("PATCH") ? "{\"status\":\"IN_PROGRESS\"}" : null),
                status,
                code,
                path);
    }

    @Test
    void enforcesQueryAndContentTypeRules() throws Exception {
        var id = createTicket("NEW");
        var before = snapshot(id);
        assertError(
                send("PATCH", statusPath(id) + "?extra=1", "{\"status\":\"IN_PROGRESS\"}"),
                400,
                "INVALID_REQUEST",
                statusPath(id));
        assertError(
                send("GET", historyPath(id) + "?extra=1", null),
                400,
                "INVALID_REQUEST",
                historyPath(id));
        assertError(
                send("PATCH", statusPath(id), "{\"status\":\"IN_PROGRESS\"}", "text/plain"),
                415,
                "UNSUPPORTED_MEDIA_TYPE",
                statusPath(id));
        assertThat(snapshot(id)).isEqualTo(before);
        assertThat(history(id)).isEmpty();
    }

    @Test
    void rollsBackStatusAndClosingTimeWhenHistoryInsertFails() throws Exception {
        var id = createTicket("IN_PROGRESS");
        var before = snapshot(id);
        var entries = history(id);
        jdbc.execute(
                "ALTER TABLE ticket_status_history ADD CONSTRAINT ck_test_history_failure CHECK (to_status <> 'RESOLVED') NOT VALID");
        try {
            var response = change(id, "RESOLVED");
            assertError(response, 500, "INTERNAL_ERROR", statusPath(id));
            assertThat(response.body().toString())
                    .doesNotContain("ck_test", "Exception", "insert into");
            assertThat(snapshot(id)).isEqualTo(before);
            assertThat(history(id)).isEqualTo(entries);
        } finally {
            jdbc.execute(
                    "ALTER TABLE ticket_status_history DROP CONSTRAINT ck_test_history_failure");
        }
        assertThat(change(id, "RESOLVED").status()).isEqualTo(200);
        assertThat(history(id)).hasSize(entries.size() + 1);
    }

    @Test
    void rejectsClockEarlierThanCreationWithoutSavingHistory() throws Exception {
        var id = createTicket("NEW");
        jdbc.update(
                "UPDATE tickets SET created_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(NOW.plusSeconds(1), ZoneOffset.UTC),
                id);
        var before = snapshot(id);
        assertError(change(id, "CANCELLED"), 500, "INTERNAL_ERROR", statusPath(id));
        assertThat(snapshot(id)).isEqualTo(before);
        assertThat(history(id)).isEmpty();
    }
}
