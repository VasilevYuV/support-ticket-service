package ru.vasilev.support.common.contract;

import static org.assertj.core.api.Assertions.*;

import java.net.http.HttpHeaders;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class OpenApiContractTest {
    private static final String PATH = "/api/tickets/123e4567-e89b-42d3-a456-426614174000";
    private static final HttpHeaders HEADERS = headers("application/json", true);
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void acceptsTicketWithNullableDatesAndLocation() {
        OpenApiContract.assertResponse("POST", "/api/tickets", 201, ticket(), HEADERS);
        OpenApiContract.assertResponse("GET", PATH, 200, ticket(), HEADERS);
    }

    @ParameterizedTest
    @CsvSource({
        "id,\"invalid-uuid\"",
        "status,\"UNKNOWN\"",
        "priority,null",
        "createdAt,\"2026-02-30T09:00:00Z\"",
        "overdue,\"false\"",
        "title,null",
        "unexpected,true"
    })
    void rejectsResponseThatViolatesSchema(String field, String value) {
        var body = ticket();
        body.set(field, json.readTree(value));
        assertThatThrownBy(() -> OpenApiContract.assertResponse("GET", PATH, 200, body, HEADERS))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void rejectsMissingRequiredField() {
        var body = ticket();
        body.remove("dueAt");
        assertThatThrownBy(() -> OpenApiContract.assertResponse("GET", PATH, 200, body, HEADERS))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void rejectsMissingLocationAndWrongContentType() {
        assertThatThrownBy(() ->
                        OpenApiContract.assertResponse(
                                "POST",
                                "/api/tickets",
                                201,
                                ticket(),
                                headers("application/json", false)))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() ->
                        OpenApiContract.assertResponse(
                                "GET", PATH, 200, ticket(), headers("text/plain", false)))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void rejectsUndeclaredStatusRouteAndSuccessfulUnsupportedMethod() {
        assertThatThrownBy(
                        () -> OpenApiContract.assertResponse("GET", PATH, 202, ticket(), HEADERS))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() ->
                        OpenApiContract.assertResponse(
                                "GET", "/api/unknown", 200, ticket(), HEADERS))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() ->
                        OpenApiContract.assertResponse("DELETE", PATH, 200, ticket(), HEADERS))
                .isInstanceOf(AssertionError.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409, 405, 406, 415, 500})
    void resolvesSharedErrorResponseSchemas(int status) {
        JsonNode error =
                json.readTree(
                        """
                        {"timestamp":"2026-11-11T09:00:00Z","status":%d,"code":"INVALID_REQUEST",
                         "message":"Ошибка запроса.","path":"/api/tickets/123e4567-e89b-42d3-a456-426614174000/status",
                         "violations":[]}
                        """
                                .formatted(status));
        OpenApiContract.assertResponse("PATCH", PATH + "/status", status, error, HEADERS);
        if (status == 405) {
            OpenApiContract.assertResponse("DELETE", PATH, status, error, HEADERS);
        }
    }

    private ObjectNode ticket() {
        return (ObjectNode)
                json.readTree(
                        """
                        {"id":"123e4567-e89b-42d3-a456-426614174000","title":"Заявка","description":"",
                         "category":{"id":1,"name":"Оборудование"},"priority":"MEDIUM","status":"NEW",
                         "createdAt":"2026-11-11T09:00:00Z","dueAt":null,"closedAt":null,"overdue":false}
                        """);
    }

    private static HttpHeaders headers(String contentType, boolean location) {
        var values = new java.util.HashMap<String, List<String>>();
        values.put("Content-Type", List.of(contentType));
        if (location) {
            values.put("Location", List.of(PATH));
        }
        return HttpHeaders.of(values, (name, value) -> true);
    }
}
