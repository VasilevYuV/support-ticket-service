package ru.vasilev.support.ticket.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.vasilev.support.config.PostgresTestConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, TicketApiTest.FixedTimeConfiguration.class})
class TicketApiTest {
    private static final Instant NOW = Instant.parse("2026-11-11T09:00:00.123456Z");
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @LocalServerPort private int port;
    private long categoryId;

    @BeforeEach
    void prepareDatabase() {
        jdbc.update("DELETE FROM ticket_status_history");
        jdbc.update("DELETE FROM tickets");
        categoryId =
                jdbc.queryForObject(
                        "SELECT id FROM categories WHERE name = ?", Long.class, "Оборудование");
    }

    @AfterEach
    void closeHttpClient() {
        client.close();
    }

    @Test
    void listsCategoriesInIdOrder() throws Exception {
        var response = send("GET", "/api/categories", null);
        assertThat(response.status()).isEqualTo(200);
        var expected = jdbc.queryForList("SELECT id, name FROM categories ORDER BY id");
        assertThat(response.body().size()).isEqualTo(4);
        for (int index = 0; index < expected.size(); index++) {
            assertThat(response.body().get(index).get("id").longValue())
                    .isEqualTo(expected.get(index).get("id"));
            assertThat(response.body().get(index).get("name").stringValue())
                    .isEqualTo(expected.get(index).get("name"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOW", "MEDIUM", "HIGH"})
    void createsAndReadsFullTicket(String priority) throws Exception {
        var created =
                send(
                        "POST",
                        "/api/tickets",
                        """
                        {"title":"  Не работает принтер  ","categoryId":%d,
                         "description":"Проверено питание.","priority":"%s",
                         "dueAt":"2026-11-12T12:00:00.123456+03:00"}
                        """
                                .formatted(categoryId, priority));

        assertThat(created.status()).isEqualTo(201);
        var body = created.body();
        var id = UUID.fromString(body.get("id").stringValue());
        assertThat(created.location()).isEqualTo("/api/tickets/" + id);
        assertThat(body.size()).isEqualTo(10);
        assertThat(body.get("title").stringValue()).isEqualTo("Не работает принтер");
        assertThat(body.get("description").stringValue()).isEqualTo("Проверено питание.");
        assertThat(body.get("category").get("id").longValue()).isEqualTo(categoryId);
        assertThat(body.get("category").get("name").stringValue()).isEqualTo("Оборудование");
        assertThat(body.get("priority").stringValue()).isEqualTo(priority);
        assertThat(body.get("status").stringValue()).isEqualTo("NEW");
        assertThat(body.get("createdAt").stringValue()).isEqualTo(NOW.toString());
        assertThat(body.get("dueAt").stringValue()).isEqualTo("2026-11-12T09:00:00.123456Z");
        assertThat(body.get("closedAt").isNull()).isTrue();
        assertThat(body.get("overdue").booleanValue()).isFalse();

        var savedBeforeRead = jdbc.queryForMap("SELECT * FROM tickets WHERE id = ?", id);
        assertThat(savedBeforeRead)
                .containsEntry("title", "Не работает принтер")
                .containsEntry("description", "Проверено питание.")
                .containsEntry("priority", priority)
                .containsEntry("status", "NEW")
                .containsEntry("category_id", categoryId)
                .containsEntry("closed_at", null);
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT created_at FROM tickets WHERE id = ?",
                                        OffsetDateTime.class,
                                        id)
                                .toInstant())
                .isEqualTo(NOW);
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT due_at FROM tickets WHERE id = ?",
                                        OffsetDateTime.class,
                                        id)
                                .toInstant())
                .isEqualTo(Instant.parse("2026-11-12T09:00:00.123456Z"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM ticket_status_history WHERE ticket_id = ?",
                                Integer.class,
                                id))
                .isZero();

        var read = send("GET", created.location(), null);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.body()).isEqualTo(body);
        assertThat(jdbc.queryForMap("SELECT * FROM tickets WHERE id = ?", id))
                .isEqualTo(savedBeforeRead);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_status_history", Integer.class))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"description\":null,\"priority\":null,\"dueAt\":null"})
    void appliesDefaultsForOmittedAndNullFields(String optionalFields) throws Exception {
        var request =
                "{\"title\":\"Минимальная заявка\",\"categoryId\":"
                        + categoryId
                        + optionalFields
                        + "}";
        var first = send("POST", "/api/tickets", request);
        var second = send("POST", "/api/tickets", request);
        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(first.location()).isNotEqualTo(second.location());
        var body = first.body();
        assertThat(body.get("description").stringValue()).isEmpty();
        assertThat(body.get("priority").stringValue()).isEqualTo("MEDIUM");
        assertThat(body.get("dueAt").isNull()).isTrue();
        assertThat(body.get("closedAt").isNull()).isTrue();
        assertThat(body.get("overdue").booleanValue()).isFalse();
        assertThat(send("GET", first.location(), null).body()).isEqualTo(body);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM tickets WHERE status = 'NEW' AND description = '' AND priority = 'MEDIUM' AND due_at IS NULL AND closed_at IS NULL",
                                Integer.class))
                .isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESOLVED", "CANCELLED"})
    void readsTerminalTicketWithoutChangingItOrItsHistory(String status) throws Exception {
        var created =
                send(
                        "POST",
                        "/api/tickets",
                        "{\"title\":\"Закрытая заявка\",\"categoryId\":" + categoryId + "}");
        var id = UUID.fromString(created.body().get("id").stringValue());
        var time = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        jdbc.update(
                "UPDATE tickets SET status = ?, due_at = ?, closed_at = ? WHERE id = ?",
                status,
                time.minusDays(1),
                time,
                id);
        if (status.equals("RESOLVED")) {
            jdbc.update(
                    "INSERT INTO ticket_status_history (ticket_id, from_status, to_status, changed_at) VALUES (?, 'NEW', 'IN_PROGRESS', ?)",
                    id,
                    time);
        }
        jdbc.update(
                "INSERT INTO ticket_status_history (ticket_id, from_status, to_status, changed_at) VALUES (?, ?, ?, ?)",
                id,
                status.equals("RESOLVED") ? "IN_PROGRESS" : "NEW",
                status,
                time);
        var ticketBefore = jdbc.queryForMap("SELECT * FROM tickets WHERE id = ?", id);
        var historyBefore =
                jdbc.queryForList(
                        "SELECT * FROM ticket_status_history WHERE ticket_id = ? ORDER BY id", id);

        var response = send("GET", created.location(), null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("status").stringValue()).isEqualTo(status);
        assertThat(response.body().get("closedAt").stringValue()).isEqualTo(NOW.toString());
        assertThat(response.body().get("overdue").booleanValue()).isFalse();
        assertThat(jdbc.queryForMap("SELECT * FROM tickets WHERE id = ?", id))
                .isEqualTo(ticketBefore);
        assertThat(
                        jdbc.queryForList(
                                "SELECT * FROM ticket_status_history WHERE ticket_id = ? ORDER BY id",
                                id))
                .isEqualTo(historyBefore);
    }

    @ParameterizedTest
    @MethodSource("dueDates")
    void calculatesOverdueFromFixedClock(String dueAt, boolean overdue) throws Exception {
        var created =
                send(
                        "POST",
                        "/api/tickets",
                        """
                        {"title":"Проверка срока","categoryId":%d,"dueAt":"%s"}
                        """
                                .formatted(categoryId, dueAt));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("overdue").booleanValue()).isEqualTo(overdue);
        assertThat(send("GET", created.location(), null).body().get("overdue").booleanValue())
                .isEqualTo(overdue);
    }

    static Stream<Arguments> dueDates() {
        return Stream.of(
                Arguments.of(NOW.minusNanos(1000).toString(), true),
                Arguments.of(NOW.toString(), false),
                Arguments.of(NOW.plusNanos(1000).toString(), false));
    }

    @Test
    void acceptsUnicodeLengthLimits() throws Exception {
        var created =
                send(
                        "POST",
                        "/api/tickets",
                        """
                        {"title":"%s","categoryId":%d,"description":"%s"}
                        """
                                .formatted("😀".repeat(200), categoryId, "😀".repeat(10000)));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("title").stringValue()).isEqualTo("😀".repeat(200));
        assertThat(send("GET", created.location(), null).body()).isEqualTo(created.body());
    }

    @ParameterizedTest(name = "{index}: {1}")
    @MethodSource("invalidRequests")
    void rejectsInvalidCreationWithoutSaving(String template, String code) throws Exception {
        var response =
                send(
                        "POST",
                        "/api/tickets",
                        template.replace("CATEGORY_ID", Long.toString(categoryId)));
        assertError(response, 400, code, "/api/tickets");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tickets", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_status_history", Integer.class))
                .isZero();
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("{\"categoryId\":CATEGORY_ID}", "VALIDATION_ERROR"),
                Arguments.of("{\"title\":null,\"categoryId\":CATEGORY_ID}", "VALIDATION_ERROR"),
                Arguments.of("{\"title\":\"\",\"categoryId\":CATEGORY_ID}", "VALIDATION_ERROR"),
                Arguments.of(
                        "{\"title\":\" \\t\\n\",\"categoryId\":CATEGORY_ID}", "VALIDATION_ERROR"),
                Arguments.of("{\"title\":\"Заявка\"}", "VALIDATION_ERROR"),
                Arguments.of(
                        "{\"title\":\"Заявка\",\"categoryId\":9223372036854775807}",
                        "VALIDATION_ERROR"),
                Arguments.of("{\"title\":\"Заявка\",\"categoryId\":0}", "VALIDATION_ERROR"),
                Arguments.of(
                        "{\"title\":\"" + "😀".repeat(201) + "\",\"categoryId\":CATEGORY_ID}",
                        "VALIDATION_ERROR"),
                Arguments.of(
                        "{\"title\":\"Заявка\",\"categoryId\":CATEGORY_ID,\"description\":\""
                                + "я".repeat(10001)
                                + "\"}",
                        "VALIDATION_ERROR"),
                Arguments.of(
                        "{\"title\":\"Заявка\",\"categoryId\":CATEGORY_ID,\"priority\":\"URGENT\"}",
                        "INVALID_REQUEST"),
                Arguments.of(
                        "{\"title\":\"Заявка\",\"categoryId\":CATEGORY_ID,\"priority\":1}",
                        "INVALID_REQUEST"),
                Arguments.of(
                        "{\"title\":\"Заявка\",\"categoryId\":CATEGORY_ID,\"status\":\"RESOLVED\"}",
                        "INVALID_REQUEST"),
                Arguments.of(
                        "{\"title\":\"Заявка\",\"categoryId\":CATEGORY_ID,\"unexpected\":true}",
                        "INVALID_REQUEST"),
                Arguments.of("{", "INVALID_REQUEST"));
    }

    @Test
    void distinguishesMissingTicketFromMalformedId() throws Exception {
        var missingPath = "/api/tickets/00000000-0000-0000-0000-000000000099";
        assertError(send("GET", missingPath, null), 404, "TICKET_NOT_FOUND", missingPath);
        assertError(
                send("GET", "/api/tickets/not-a-uuid", null),
                400,
                "INVALID_REQUEST",
                "/api/tickets/not-a-uuid");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tickets", Integer.class)).isZero();
    }

    private void assertError(ApiResult response, int status, String code, String path) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.body().size()).isEqualTo(6);
        assertThat(response.body().get("status").intValue()).isEqualTo(status);
        assertThat(response.body().get("code").stringValue()).isEqualTo(code);
        assertThat(response.body().get("path").stringValue()).isEqualTo(path);
        assertThat(response.body().get("timestamp").stringValue()).isEqualTo(NOW.toString());
        assertThat(response.body().get("message").stringValue()).isNotBlank();
        assertThat(response.body().get("violations").isArray()).isTrue();
    }

    private ApiResult send(String method, String path, String body) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(10));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new ApiResult(
                response.statusCode(),
                json.readTree(response.body()),
                response.headers().firstValue("Location").orElse(null));
    }

    private record ApiResult(int status, JsonNode body, String location) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTimeConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW.plusNanos(789), ZoneOffset.UTC);
        }
    }
}
