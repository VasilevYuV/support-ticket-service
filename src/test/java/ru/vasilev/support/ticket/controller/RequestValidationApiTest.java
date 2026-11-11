package ru.vasilev.support.ticket.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.vasilev.support.config.ApiTestSupport;

class RequestValidationApiTest extends ApiTestSupport {
    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("invalidTypes")
    void rejectsCoercionAndInvalidValues(String field, String rawValue) throws Exception {
        var response = send("POST", "/api/tickets", bodyWith(field, rawValue));
        assertError(response, 400, "INVALID_REQUEST", "/api/tickets");
        assertField(response, field);
        assertEmptyDatabase();
    }

    static Stream<Arguments> invalidTypes() {
        return Stream.of(
                Arguments.of("title", "123"),
                Arguments.of("title", "true"),
                Arguments.of("title", "[]"),
                Arguments.of("title", "{}"),
                Arguments.of("description", "123"),
                Arguments.of("description", "false"),
                Arguments.of("description", "[]"),
                Arguments.of("description", "{}"),
                Arguments.of("categoryId", "\"1\""),
                Arguments.of("categoryId", "1.0"),
                Arguments.of("categoryId", "1.9"),
                Arguments.of("categoryId", "1e0"),
                Arguments.of("categoryId", "true"),
                Arguments.of("categoryId", "[]"),
                Arguments.of("categoryId", "{}"),
                Arguments.of("categoryId", "9223372036854775808"),
                Arguments.of("categoryId", "-9223372036854775809"),
                Arguments.of("priority", "0"),
                Arguments.of("priority", "false"),
                Arguments.of("priority", "[]"),
                Arguments.of("priority", "{}"),
                Arguments.of("priority", "\"\""),
                Arguments.of("priority", "\"high\""),
                Arguments.of("priority", "\" HIGH \""),
                Arguments.of("dueAt", "0"),
                Arguments.of("dueAt", "1.5"),
                Arguments.of("dueAt", "true"),
                Arguments.of("dueAt", "[]"),
                Arguments.of("dueAt", "{}"),
                Arguments.of("title", "\"\\u0000\""),
                Arguments.of("description", "\"\\uD800\""),
                Arguments.of("description", "\"\\uDC00\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "status", "createdAt", "closedAt", "overdue", "extra"})
    void rejectsServerAndUnknownFields(String field) throws Exception {
        var response = send("POST", "/api/tickets", bodyWith(field, "null"));
        assertError(response, 400, "INVALID_REQUEST", "/api/tickets");
        assertField(response, field);
        assertEmptyDatabase();
    }

    @ParameterizedTest(name = "{index}")
    @ValueSource(
            strings = {
                "",
                "null",
                "[]",
                "1",
                "true",
                "\"text\"",
                "{",
                "{\"title\":\"Заявка\",\"categoryId\":CATEGORY_ID,}",
                "{\"title\":\"Первая\",\"title\":\"Вторая\",\"categoryId\":CATEGORY_ID}",
                "{\"title\":\"Заявка\",\"categoryId\":CATEGORY_ID,\"categoryId\":CATEGORY_ID}",
                "{\"title\":\"Заявка\",\"categoryId\":CATEGORY_ID} {}"
            })
    void rejectsInvalidJsonStructure(String template) throws Exception {
        var response =
                send(
                        "POST",
                        "/api/tickets",
                        template.replace("CATEGORY_ID", Long.toString(categoryId)));
        assertError(response, 400, "INVALID_REQUEST", "/api/tickets");
        assertEmptyDatabase();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "2026-11-11",
                "2026-11-11T09:00:00",
                "2026-11-11 09:00:00Z",
                "2026-11-11t09:00:00z",
                "2026-11-11T09:00Z",
                "2026-11-11T09:00:60Z",
                "2026-11-11T24:00:00Z",
                "2026-02-29T09:00:00Z",
                "2026-04-31T09:00:00Z",
                "2026-13-01T09:00:00Z",
                "2026-11-11T09:00:00.Z",
                "2026-11-11T09:00:00.1234567Z",
                "2026-11-11T09:00:00.1234560Z",
                "2026-11-11T09:00:00+0300",
                "2026-11-11T09:00:00+03:00:00",
                "2026-11-11T09:00:00+19:00",
                "2026-11-11T09:00:00+03:60",
                "0000-01-01T00:00:00Z",
                "+10000-01-01T00:00:00Z",
                "-0001-01-01T00:00:00Z",
                "0001-01-01T00:00:00+01:00",
                "9999-12-31T23:59:59-01:00",
                " 2026-11-11T09:00:00Z",
                "2026-11-11T09:00:00Z "
            })
    void rejectsInvalidDateBeforeSaving(String value) throws Exception {
        var response =
                send("POST", "/api/tickets", bodyWith("dueAt", json.writeValueAsString(value)));
        assertError(response, 400, "INVALID_REQUEST", "/api/tickets");
        assertField(response, "dueAt");
        assertEmptyDatabase();
    }

    @ParameterizedTest
    @CsvSource({
        "2024-02-29T12:00:00+03:00,2024-02-29T09:00:00Z",
        "2026-11-11T05:30:00-03:30,2026-11-11T09:00:00Z",
        "2026-11-11T09:00:00.1Z,2026-11-11T09:00:00.100Z",
        "2026-11-11T09:00:00.123456Z,2026-11-11T09:00:00.123456Z",
        "0001-01-01T00:00:00Z,0001-01-01T00:00:00Z",
        "9999-12-31T23:59:59.999999Z,9999-12-31T23:59:59.999999Z"
    })
    void acceptsCalendarAndPrecisionBoundaries(String value, String normalized) throws Exception {
        var created =
                send("POST", "/api/tickets", bodyWith("dueAt", json.writeValueAsString(value)));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("dueAt").stringValue()).isEqualTo(normalized);
        assertThat(send("GET", created.location(), null).body()).isEqualTo(created.body());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "1-1-1-1-1",
                "00000000000000000000000000000001",
                "{00000000-0000-0000-0000-000000000001}",
                "gggggggg-0000-0000-0000-000000000001"
            })
    void rejectsNonCanonicalUuid(String value) throws Exception {
        var path = "/api/tickets/" + value.replace("{", "%7B").replace("}", "%7D");
        var response = send("GET", path, null);
        assertError(response, 400, "INVALID_REQUEST", path);
        assertField(response, "id");
    }

    @Test
    void acceptsUppercaseCanonicalUuid() throws Exception {
        var path = "/api/tickets/ABCDEFAB-0000-0000-0000-ABCDEFABCDEF";
        assertError(send("GET", path, null), 404, "TICKET_NOT_FOUND", path);
    }

    @ParameterizedTest
    @CsvSource({
        "GET,/api/categories",
        "POST,/api/tickets",
        "GET,/api/tickets/00000000-0000-0000-0000-000000000099"
    })
    void rejectsQueryParametersBeforeLookup(String method, String path) throws Exception {
        for (String query :
                new String[] {
                    "?unexpected=1", "?unexpected=", "?unexpected", "?unexpected=1&unexpected=2"
                }) {
            var response =
                    send(
                            method,
                            path + query,
                            method.equals("POST") ? bodyWith("title", "\"Заявка\"") : null);
            assertError(response, 400, "INVALID_REQUEST", path);
            assertField(response, "unexpected");
        }
        assertEmptyDatabase();
    }

    @ParameterizedTest
    @CsvSource({
        "DELETE,/api/categories",
        "DELETE,/api/tickets/00000000-0000-0000-0000-000000000099"
    })
    void returnsUniformMethodErrorWithAllowHeader(String method, String path) throws Exception {
        var response = send(method, path, null);
        assertError(response, 405, "METHOD_NOT_ALLOWED", path);
        assertThat(response.headers().firstValue("Allow"))
                .hasValueSatisfying(value -> assertThat(value).contains("GET"));
    }

    @ParameterizedTest
    @MethodSource("unsupportedMediaTypes")
    void rejectsNonJsonMediaType(String contentType) throws Exception {
        var response = send("POST", "/api/tickets", bodyWith("title", "\"Заявка\""), contentType);
        assertError(response, 415, "UNSUPPORTED_MEDIA_TYPE", "/api/tickets");
        assertEmptyDatabase();
    }

    static Stream<String> unsupportedMediaTypes() {
        return Stream.of(
                "text/plain", "application/xml", "application/x-www-form-urlencoded", null);
    }

    @Test
    void returnsJsonErrorForUnsupportedResponseFormat() throws Exception {
        var request =
                HttpRequest.newBuilder(uri("/api/categories"))
                        .header("Accept", "text/plain")
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
        assertError(send(request), 406, "NOT_ACCEPTABLE", "/api/categories");
    }

    @Test
    void hidesDatabaseFailureAndKeepsExistingData() throws Exception {
        assertThat(
                        send("POST", "/api/tickets", bodyWith("title", "\"Существующая заявка\""))
                                .status())
                .isEqualTo(201);
        var before = jdbc.queryForList("SELECT * FROM tickets ORDER BY id");
        jdbc.execute(
                "ALTER TABLE tickets ADD CONSTRAINT ck_test_reject_insert CHECK (false) NOT VALID");
        try {
            var response = send("POST", "/api/tickets", bodyWith("title", "\"Новая заявка\""));
            assertError(response, 500, "INTERNAL_ERROR", "/api/tickets");
            assertThat(response.body().get("message").stringValue())
                    .isEqualTo("Внутренняя ошибка сервера.");
            assertThat(response.body().toString())
                    .doesNotContain("ck_test", "insert into", "Exception", "org.postgresql");
            assertThat(jdbc.queryForList("SELECT * FROM tickets ORDER BY id")).isEqualTo(before);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM ticket_status_history", Integer.class))
                    .isZero();
        } finally {
            jdbc.execute("ALTER TABLE tickets DROP CONSTRAINT ck_test_reject_insert");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u00a0", "\u2007", "\u202f", " \t\n"})
    void rejectsWhitespaceOnlyTitle(String whitespace) throws Exception {
        var response =
                send(
                        "POST",
                        "/api/tickets",
                        bodyWith("title", json.writeValueAsString(whitespace)));
        assertError(response, 400, "VALIDATION_ERROR", "/api/tickets");
        assertField(response, "title");
        assertEmptyDatabase();
    }

    @Test
    void stripsUnicodeWhitespaceWithoutChangingInternalSpaces() throws Exception {
        var response =
                send(
                        "POST",
                        "/api/tickets",
                        bodyWith("title", json.writeValueAsString("\u00a0Текст  заявки\u202f")));
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().get("title").stringValue()).isEqualTo("Текст  заявки");
    }

    private String bodyWith(String field, String rawValue) {
        String title = field.equals("title") ? rawValue : "\"Заявка\"";
        String category = field.equals("categoryId") ? rawValue : Long.toString(categoryId);
        String extra =
                field.equals("title") || field.equals("categoryId")
                        ? ""
                        : ",\"" + field + "\":" + rawValue;
        return "{\"title\":" + title + ",\"categoryId\":" + category + extra + "}";
    }

    private void assertField(ApiResult response, String field) {
        assertThat(response.body().get("violations").size()).isEqualTo(1);
        assertThat(response.body().get("violations").get(0).get("field").stringValue())
                .isEqualTo(field);
        assertThat(response.body().get("violations").get(0).get("message").stringValue())
                .isNotBlank();
    }

    private void assertEmptyDatabase() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tickets", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_status_history", Integer.class))
                .isZero();
    }
}
