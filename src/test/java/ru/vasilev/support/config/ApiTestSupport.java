package ru.vasilev.support.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, ApiTestSupport.FixedTimeConfiguration.class})
public abstract class ApiTestSupport {
    protected static final Instant NOW = Instant.parse("2026-11-11T09:00:00.123456Z");
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected JsonMapper json;
    @LocalServerPort private int port;
    protected long categoryId;

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

    protected void assertError(ApiResult response, int status, String code, String path) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.body().size()).isEqualTo(6);
        assertThat(response.body().get("status").intValue()).isEqualTo(status);
        assertThat(response.body().get("code").stringValue()).isEqualTo(code);
        assertThat(response.body().get("path").stringValue()).isEqualTo(path);
        assertThat(response.body().get("timestamp").stringValue()).isEqualTo(NOW.toString());
        assertThat(response.body().get("message").stringValue()).isNotBlank();
        assertThat(response.body().get("violations").isArray()).isTrue();
    }

    protected URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    protected ApiResult send(String method, String path, String body) throws Exception {
        return send(method, path, body, body == null ? null : "application/json");
    }

    protected ApiResult send(String method, String path, String body, String contentType)
            throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        request.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return send(request.build());
    }

    protected ApiResult send(HttpRequest request) throws Exception {
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new ApiResult(
                response.statusCode(),
                json.readTree(response.body()),
                response.headers().firstValue("Location").orElse(null),
                response.headers());
    }

    protected record ApiResult(int status, JsonNode body, String location, HttpHeaders headers) {}

    @TestConfiguration(proxyBeanMethods = false)
    public static class FixedTimeConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW.plusNanos(789), ZoneOffset.UTC);
        }
    }
}
