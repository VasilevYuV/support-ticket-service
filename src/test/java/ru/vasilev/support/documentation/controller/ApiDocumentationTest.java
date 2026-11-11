package ru.vasilev.support.documentation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import ru.vasilev.support.config.PostgresTestConfiguration;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class ApiDocumentationTest {
    @LocalServerPort private int port;

    @Test
    void servesTheSameContractAsApiTests() throws Exception {
        try (var client = HttpClient.newHttpClient();
                var contract = getClass().getResourceAsStream("/contract/openapi.yaml")) {
            var response = get(client, "/openapi.yaml");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(contract).isNotNull();
            assertThat(response.body())
                    .isEqualTo(new String(contract.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void configuresSwaggerUiForThePublishedContract() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var redirect = get(client, "/swagger-ui.html");
            assertThat(redirect.statusCode()).isEqualTo(302);
            assertThat(redirect.headers().firstValue("Location"))
                    .hasValue("/swagger-ui/index.html");
            var page = get(client, "/swagger-ui/index.html");
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("swagger-ui-bundle.js");
            var config = get(client, "/v3/api-docs/swagger-config");
            assertThat(config.statusCode()).isEqualTo(200);
            assertThat(new JsonMapper().readTree(config.body()).get("url").stringValue())
                    .isEqualTo("/openapi.yaml");
            assertThat(get(client, "/swagger-ui/swagger-ui-bundle.js").statusCode()).isEqualTo(200);
            assertThat(get(client, "/v3/api-docs").statusCode()).isEqualTo(404);
        }
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
