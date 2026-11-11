package ru.vasilev.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import ru.vasilev.support.config.PostgresTestConfiguration;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class ApplicationStartupTest {
    @LocalServerPort private int port;

    @Test
    void healthEndpointRespondsAfterStartup() throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request =
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/actuator/health"))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            JSONAssert.assertEquals("{\"status\":\"UP\"}", response.body(), false);
        }
    }
}
