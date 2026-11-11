package ru.vasilev.support.ticket.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.vasilev.support.config.ApiTestSupport;

class TicketDetailsApiTest extends ApiTestSupport {
    private static final String REPLACEMENT =
            """
            {"description":"Проверено питание.","priority":"HIGH","dueAt":"2026-11-11T12:00:00.123455+03:00"}
            """;
    private static final String CLEAR =
            """
            {"description":"","priority":"MEDIUM","dueAt":null}
            """;

    @ParameterizedTest
    @ValueSource(strings = {"NEW", "IN_PROGRESS"})
    void replacesAndClearsDetailsWithoutChangingOtherData(String status) throws Exception {
        var id = createTicket(status);
        var original = snapshot(id);
        var history = history(id);
        var response = send("PUT", path(id), REPLACEMENT);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().size()).isEqualTo(10);
        assertThat(response.body().get("description").stringValue())
                .isEqualTo("Проверено питание.");
        assertThat(response.body().get("priority").stringValue()).isEqualTo("HIGH");
        assertThat(response.body().get("dueAt").stringValue())
                .isEqualTo("2026-11-11T09:00:00.123455Z");
        assertThat(response.body().get("overdue").booleanValue()).isTrue();
        var changed = snapshot(id);
        assertThat(changed)
                .containsEntry("description", "Проверено питание.")
                .containsEntry("priority", "HIGH");
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT due_at FROM tickets WHERE id = ?",
                                        OffsetDateTime.class,
                                        id)
                                .toInstant())
                .isEqualTo(NOW.minusNanos(1000));
        assertImmutableFields(original, changed);
        assertThat(send("GET", "/api/tickets/" + id, null).body()).isEqualTo(response.body());
        assertThat(send("PUT", path(id), REPLACEMENT).body()).isEqualTo(response.body());
        assertThat(snapshot(id)).isEqualTo(changed);
        var cleared = send("PUT", path(id), CLEAR);
        assertThat(cleared.status()).isEqualTo(200);
        assertThat(cleared.body().get("overdue").booleanValue()).isFalse();
        assertThat(cleared.body().get("dueAt").isNull()).isTrue();
        var saved = snapshot(id);
        assertThat(saved)
                .containsEntry("description", "")
                .containsEntry("priority", "MEDIUM")
                .containsEntry("due_at", null);
        assertImmutableFields(original, saved);
        assertThat(history(id)).isEqualTo(history);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESOLVED", "CANCELLED"})
    void rejectsTerminalTicketWithoutChangingSnapshot(String status) throws Exception {
        var id = createTicket(status);
        var before = snapshot(id);
        var history = history(id);
        assertError(send("PUT", path(id), REPLACEMENT), 409, "STATE_CONFLICT", path(id));
        assertThat(snapshot(id)).isEqualTo(before);
        assertThat(history(id)).isEqualTo(history);
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void rejectsInvalidBodyBeforeLookupAndWithoutPartialUpdate(String body, String field)
            throws Exception {
        var id = createTicket("NEW");
        var before = snapshot(id);
        for (var target :
                new UUID[] {id, UUID.fromString("00000000-0000-0000-0000-000000000099")}) {
            var response = send("PUT", path(target), body);
            assertThat(response.status()).isEqualTo(400);
            assertError(response, 400, response.body().get("code").stringValue(), path(target));
            assertThat(response.body().get("code").stringValue())
                    .isIn("INVALID_REQUEST", "VALIDATION_ERROR");
            if (field != null) {
                assertThat(response.body().get("violations").get(0).get("field").stringValue())
                        .isEqualTo(field);
            }
        }
        assertThat(snapshot(id)).isEqualTo(before);
        assertThat(history(id)).isEmpty();
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("{\"priority\":\"HIGH\",\"dueAt\":null}", "description"),
                Arguments.of("{\"description\":\"\",\"dueAt\":null}", "priority"),
                Arguments.of("{\"description\":\"\",\"priority\":\"HIGH\"}", "dueAt"),
                Arguments.of(body("null", "\"HIGH\"", "null"), "description"),
                Arguments.of(body("\"\"", "null", "null"), "priority"),
                Arguments.of(body("42", "\"HIGH\"", "null"), "description"),
                Arguments.of(body("[]", "\"HIGH\"", "null"), "description"),
                Arguments.of(
                        body("\"" + "я".repeat(10001) + "\"", "\"HIGH\"", "null"), "description"),
                Arguments.of(body("\"\\u0000\"", "\"HIGH\"", "null"), "description"),
                Arguments.of(body("\"\\ud800\"", "\"HIGH\"", "null"), "description"),
                Arguments.of(body("\"\"", "1", "null"), "priority"),
                Arguments.of(body("\"\"", "\"high\"", "null"), "priority"),
                Arguments.of(body("\"\"", "\"\"", "null"), "priority"),
                Arguments.of(body("\"\"", "\"HIGH\"", "12"), "dueAt"),
                Arguments.of(body("\"\"", "\"HIGH\"", "\"2026-11-11T09:00:00\""), "dueAt"),
                Arguments.of(body("\"\"", "\"HIGH\"", "\"2026-11-11T09:00:00.1234560Z\""), "dueAt"),
                Arguments.of(body("\"\"", "\"HIGH\"", "\"2026-02-30T09:00:00Z\""), "dueAt"),
                Arguments.of("null", null),
                Arguments.of("[]", null),
                Arguments.of("", null),
                Arguments.of(CLEAR + "{}", null),
                Arguments.of(
                        "{\"description\":\"\",\"priority\":\"HIGH\",\"priority\":\"LOW\",\"dueAt\":null}",
                        null));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "title",
                "categoryId",
                "id",
                "status",
                "createdAt",
                "closedAt",
                "overdue",
                "extra"
            })
    void rejectsImmutableAndUnknownFields(String field) throws Exception {
        var id = createTicket("NEW");
        var before = snapshot(id);
        var invalid = CLEAR.strip().replace("}", ",\"" + field + "\":null}");
        var response = send("PUT", path(id), invalid);
        assertError(response, 400, "INVALID_REQUEST", path(id));
        assertThat(response.body().get("violations").get(0).get("field").stringValue())
                .isEqualTo(field);
        assertThat(snapshot(id)).isEqualTo(before);
        assertThat(history(id)).isEmpty();
    }

    @Test
    void acceptsMaximumUnicodeDescriptionAndAllPriorities() throws Exception {
        var id = createTicket("NEW");
        var description = "😀".repeat(10000);
        for (String priority : new String[] {"LOW", "MEDIUM", "HIGH"}) {
            var request =
                    body(json.writeValueAsString(description), "\"" + priority + "\"", "null");
            var response = send("PUT", path(id), request);
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body().get("description").stringValue()).isEqualTo(description);
            assertThat(snapshot(id))
                    .containsEntry("description", description)
                    .containsEntry("priority", priority);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "1-1-1-1-1,400,INVALID_REQUEST",
        "00000000-0000-0000-0000-000000000099,404,TICKET_NOT_FOUND"
    })
    void distinguishesInvalidAndMissingUuid(String id, int status, String code) throws Exception {
        var path = "/api/tickets/" + id + "/details";
        assertError(send("PUT", path, CLEAR), status, code, path);
    }

    @Test
    void rejectsQueryAndNonJsonBodyWithoutChanges() throws Exception {
        var id = createTicket("NEW");
        var before = snapshot(id);
        assertError(send("PUT", path(id) + "?extra=1", CLEAR), 400, "INVALID_REQUEST", path(id));
        assertError(
                send("PUT", path(id), CLEAR, "text/plain"),
                415,
                "UNSUPPORTED_MEDIA_TYPE",
                path(id));
        assertThat(snapshot(id)).isEqualTo(before);
    }

    @Test
    void rollsBackEveryEditableFieldWhenDatabaseRejectsUpdate() throws Exception {
        var id = createTicket("IN_PROGRESS");
        var before = snapshot(id);
        var history = history(id);
        jdbc.execute(
                "ALTER TABLE tickets ADD CONSTRAINT ck_test_reject_update CHECK (priority <> 'HIGH') NOT VALID");
        try {
            assertError(send("PUT", path(id), REPLACEMENT), 500, "INTERNAL_ERROR", path(id));
            assertThat(snapshot(id)).isEqualTo(before);
            assertThat(history(id)).isEqualTo(history);
        } finally {
            jdbc.execute("ALTER TABLE tickets DROP CONSTRAINT ck_test_reject_update");
        }
    }

    @Test
    void checksTerminalStateAfterWaitingForRowLock() throws Exception {
        var id = createTicket("NEW");
        var before = snapshot(id);
        try (var connection = jdbc.getDataSource().getConnection();
                var executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var lock =
                    connection.prepareStatement("SELECT id FROM tickets WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, id);
                try (var rows = lock.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                }
            }
            int blockerPid;
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
                rows.next();
                blockerPid = rows.getInt(1);
            }
            var pending = executor.submit(() -> send("PUT", path(id), REPLACEMENT));
            try {
                await().atMost(Duration.ofSeconds(5))
                        .until(() ->
                                jdbc.queryForObject(
                                        "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))",
                                        Boolean.class,
                                        blockerPid));
                try (var update =
                        connection.prepareStatement(
                                "UPDATE tickets SET status = 'CANCELLED', closed_at = created_at WHERE id = ?")) {
                    update.setObject(1, id);
                    update.executeUpdate();
                }
                try (var history =
                        connection.prepareStatement(
                                "INSERT INTO ticket_status_history (ticket_id, from_status, to_status, changed_at) SELECT id, 'NEW', 'CANCELLED', created_at FROM tickets WHERE id = ?")) {
                    history.setObject(1, id);
                    history.executeUpdate();
                }
                connection.commit();
                assertError(pending.get(10, TimeUnit.SECONDS), 409, "STATE_CONFLICT", path(id));
                var after = snapshot(id);
                assertThat(after)
                        .containsEntry("description", before.get("description"))
                        .containsEntry("priority", before.get("priority"))
                        .containsEntry("due_at", before.get("due_at"))
                        .containsEntry("status", "CANCELLED");
                assertThat(history(id)).hasSize(1);
            } finally {
                connection.rollback();
            }
        }
    }

    private UUID createTicket(String status) throws Exception {
        var created =
                send(
                        "POST",
                        "/api/tickets",
                        "{\"title\":\"Заявка\",\"categoryId\":"
                                + categoryId
                                + ",\"description\":\"Исходное\",\"priority\":\"LOW\"}");
        assertThat(created.status()).isEqualTo(201);
        var id = UUID.fromString(created.body().get("id").stringValue());
        var time = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        if (!status.equals("NEW")) {
            boolean terminal = status.equals("RESOLVED") || status.equals("CANCELLED");
            jdbc.update(
                    "UPDATE tickets SET status = ?, closed_at = ? WHERE id = ?",
                    status,
                    terminal ? time : null,
                    id);
            if (!status.equals("CANCELLED")) {
                jdbc.update(
                        "INSERT INTO ticket_status_history (ticket_id, from_status, to_status, changed_at) VALUES (?, 'NEW', 'IN_PROGRESS', ?)",
                        id,
                        time);
            }
            if (terminal) {
                jdbc.update(
                        "INSERT INTO ticket_status_history (ticket_id, from_status, to_status, changed_at) VALUES (?, ?, ?, ?)",
                        id,
                        status.equals("RESOLVED") ? "IN_PROGRESS" : "NEW",
                        status,
                        time);
            }
        }
        return id;
    }

    private static String body(String description, String priority, String dueAt) {
        return "{\"description\":"
                + description
                + ",\"priority\":"
                + priority
                + ",\"dueAt\":"
                + dueAt
                + "}";
    }

    private String path(UUID id) {
        return "/api/tickets/" + id + "/details";
    }

    private Map<String, Object> snapshot(UUID id) {
        return jdbc.queryForMap("SELECT * FROM tickets WHERE id = ?", id);
    }

    private List<Map<String, Object>> history(UUID id) {
        return jdbc.queryForList(
                "SELECT * FROM ticket_status_history WHERE ticket_id = ? ORDER BY id", id);
    }

    private void assertImmutableFields(Map<String, Object> before, Map<String, Object> after) {
        for (String field :
                new String[] {"id", "title", "category_id", "status", "created_at", "closed_at"}) {
            assertThat(after.get(field)).as(field).isEqualTo(before.get(field));
        }
    }
}
