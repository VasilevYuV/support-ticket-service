package ru.vasilev.support.database.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.vasilev.support.SupportApplication;

class DatabaseRestartTest {
    @Test
    void restartsApplicationOnSameDatabaseWithoutChangingDataOrMigrations() {
        try (var postgres = new PostgreSQLContainer("postgres:18.6")) {
            postgres.start();
            Map<String, List<Map<String, Object>>> before;
            try (var first = startApplication(postgres)) {
                var jdbc = first.getBean(JdbcTemplate.class);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM categories", Long.class))
                        .isEqualTo(4);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM flyway_schema_history", Long.class))
                        .isEqualTo(2);
                var categoryId = jdbc.queryForObject("SELECT min(id) FROM categories", Long.class);
                var id = UUID.fromString("00000000-0000-0000-0000-000000000016");
                var createdAt = OffsetDateTime.parse("2026-11-01T09:00:00.123456Z");
                jdbc.update(
                        """
                        INSERT INTO tickets (id, title, category_id, priority, status, created_at)
                        VALUES (?, 'Проверка перезапуска', ?, 'HIGH', 'IN_PROGRESS', ?)
                        """,
                        id,
                        categoryId,
                        createdAt);
                jdbc.update(
                        """
                        INSERT INTO ticket_status_history (ticket_id, from_status, to_status, changed_at)
                        VALUES (?, 'NEW', 'IN_PROGRESS', ?)
                        """,
                        id,
                        createdAt.plusSeconds(60));
                before = snapshot(jdbc);
            }

            try (var second = startApplication(postgres)) {
                second.getBean(Flyway.class).validate();
                assertThat(snapshot(second.getBean(JdbcTemplate.class))).isEqualTo(before);
            }
        }
    }

    private ConfigurableApplicationContext startApplication(PostgreSQLContainer postgres) {
        return SpringApplication.run(
                SupportApplication.class,
                "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword());
    }

    private Map<String, List<Map<String, Object>>> snapshot(JdbcTemplate jdbc) {
        return Map.of(
                "categories", jdbc.queryForList("SELECT * FROM categories ORDER BY id"),
                "tickets", jdbc.queryForList("SELECT * FROM tickets ORDER BY id"),
                "history", jdbc.queryForList("SELECT * FROM ticket_status_history ORDER BY id"),
                "migrations",
                        jdbc.queryForList(
                                "SELECT * FROM flyway_schema_history ORDER BY installed_rank"));
    }
}
