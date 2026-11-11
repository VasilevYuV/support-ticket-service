package ru.vasilev.support.common.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.net.http.HttpHeaders;
import java.util.Locale;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.StringNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

public final class OpenApiContract {
    private static final String RESOURCE = "/contract/openapi.yaml";
    private static final JsonNode DOCUMENT = loadDocument();
    private static final SchemaRegistry SCHEMAS =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    private OpenApiContract() {}

    public static void assertResponse(
            String method, String path, int status, JsonNode body, HttpHeaders headers) {
        var paths = DOCUMENT.get("paths");
        String template =
                paths.propertyNames().stream()
                        .filter(candidate -> path.matches(candidate.replace("{id}", "[^/]+")))
                        .findFirst()
                        .orElseThrow(
                                () -> new AssertionError("Маршрут отсутствует в OpenAPI: " + path));
        String operationPointer =
                "/paths/" + escape(template) + "/" + method.toLowerCase(Locale.ROOT);
        String responsePointer;
        if (DOCUMENT.at(operationPointer).isMissingNode()) {
            assertThat(status).as("Неподдерживаемый метод %s %s", method, path).isEqualTo(405);
            responsePointer = "/components/responses/MethodNotAllowed";
        } else {
            responsePointer = operationPointer + "/responses/" + status;
        }
        var response = DOCUMENT.at(responsePointer);
        assertThat(response.isMissingNode())
                .as("Ответ %s %s: %s объявлен в OpenAPI", method, path, status)
                .isFalse();
        if (response.has("$ref")) {
            responsePointer = response.get("$ref").stringValue().substring(1);
            response = DOCUMENT.at(responsePointer);
        }
        assertThat(headers.firstValue("Content-Type"))
                .hasValueSatisfying(value ->
                        assertThat(value.split(";", 2)[0].trim()).isEqualTo("application/json"));
        assertSchema(responsePointer + "/content/application~1json/schema", body);
        if (response.has("headers")) {
            for (var name : response.get("headers").propertyNames()) {
                String value =
                        headers.firstValue(name)
                                .orElseThrow(() -> new AssertionError("Нет заголовка " + name));
                assertSchema(
                        responsePointer + "/headers/" + escape(name) + "/schema",
                        StringNode.valueOf(value));
            }
        }
    }

    private static void assertSchema(String pointer, JsonNode value) {
        assertThat(DOCUMENT.at(pointer).isMissingNode())
                .as("Схема %s существует", pointer)
                .isFalse();
        var schema =
                SCHEMAS.getSchema(SchemaLocation.of("classpath:contract/openapi.yaml#" + pointer));
        var errors =
                schema.validate(
                        value,
                        context ->
                                context.executionConfig(
                                        config -> config.formatAssertionsEnabled(true)));
        assertThat(errors).as("Соответствие OpenAPI: %s", pointer).isEmpty();
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static JsonNode loadDocument() {
        try (var input =
                Objects.requireNonNull(OpenApiContract.class.getResourceAsStream(RESOURCE))) {
            return YAMLMapper.builder().build().readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось прочитать OpenAPI.", exception);
        }
    }
}
