package ru.vasilev.support.database.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.vasilev.support.config.PostgresTestConfiguration;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class DatabaseMigrationTest {
    private static final UUID TICKET_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.parse("2026-11-01T10:00:00.123456Z");
    private static final List<String> STATUSES =
            List.of("NEW", "IN_PROGRESS", "RESOLVED", "CANCELLED");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private Flyway flyway;

    @Test
    void createsSchemaAndInitialCategories() {
        assertThat(
                        jdbc.queryForList(
                                "SELECT tablename FROM pg_tables WHERE schemaname = 'public'",
                                String.class))
                .containsExactlyInAnyOrder(
                        "categories", "tickets", "ticket_status_history", "flyway_schema_history");
        assertThat(jdbc.queryForList("SELECT name FROM categories ORDER BY id", String.class))
                .containsExactly("Оборудование", "Программное обеспечение", "Доступы", "Сеть");
        assertThat(
                        jdbc.queryForList(
                                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                                String.class))
                .containsExactly("1", "2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tickets", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_status_history", Integer.class))
                .isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void repeatedMigrationPreservesCategoriesAndHistory() {
        var categoriesBefore = jdbc.queryForList("SELECT * FROM categories ORDER BY id");
        var migrationsBefore =
                jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");

        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        assertThat(jdbc.queryForList("SELECT * FROM categories ORDER BY id"))
                .isEqualTo(categoriesBefore);
        assertThat(jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"))
                .isEqualTo(migrationsBefore);
    }

    @Test
    void createsIndexesForListingCategoriesOverdueAndHistory() {
        var indexes =
                jdbc.queryForMap(
                        "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_tickets_open_due_at'");
        assertThat(indexes.get("indexdef").toString())
                .contains("(due_at)", "NEW", "IN_PROGRESS", "due_at IS NOT NULL", "WHERE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_tickets_created_at_id'",
                                String.class))
                .contains("(created_at DESC, id DESC)");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_tickets_category'",
                                String.class))
                .contains("(category_id)");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_history_ticket_id'",
                                String.class))
                .contains("(ticket_id, id)");
    }

    @Test
    void preservesDefaultsNullableFieldsAndMicroseconds() {
        insertTicket();

        var ticket =
                jdbc.queryForMap(
                        "SELECT description, priority, status, due_at, closed_at FROM tickets WHERE id = ?",
                        TICKET_ID);
        assertThat(ticket)
                .containsEntry("description", "")
                .containsEntry("priority", "MEDIUM")
                .containsEntry("status", "NEW")
                .containsEntry("due_at", null)
                .containsEntry("closed_at", null);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT created_at FROM tickets WHERE id = ?",
                                OffsetDateTime.class,
                                TICKET_ID))
                .isEqualTo(CREATED_AT);
        assertThat(
                        jdbc.update(
                                "UPDATE tickets SET title = ?, description = ?, due_at = ? WHERE id = ?",
                                "😀".repeat(200),
                                "😀".repeat(10000),
                                CREATED_AT.minusDays(1),
                                TICKET_ID))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("invalidTicketFields")
    void rejectsInvalidTicketFields(String column, Object value) {
        insertTicket();
        assertThatThrownBy(() ->
                        jdbc.update(
                                "UPDATE tickets SET " + column + " = ? WHERE id = ?",
                                value,
                                TICKET_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    static Stream<Arguments> invalidTicketFields() {
        return Stream.of(
                Arguments.of("title", "   "),
                Arguments.of("title", "😀".repeat(201)),
                Arguments.of("description", "😀".repeat(10001)),
                Arguments.of("priority", "URGENT"),
                Arguments.of("status", "UNKNOWN"),
                Arguments.of("category_id", -1L),
                Arguments.of("id", null),
                Arguments.of("title", null),
                Arguments.of("description", null),
                Arguments.of("category_id", null),
                Arguments.of("priority", null),
                Arguments.of("status", null),
                Arguments.of("created_at", null));
    }

    @ParameterizedTest
    @MethodSource("closingTimes")
    void enforcesStatusAndClosingTime(String status, OffsetDateTime closedAt, boolean allowed) {
        insertTicket();
        if (allowed) {
            assertThat(
                            jdbc.update(
                                    "UPDATE tickets SET status = ?, closed_at = ? WHERE id = ?",
                                    status,
                                    closedAt,
                                    TICKET_ID))
                    .isEqualTo(1);
        } else {
            assertThatThrownBy(() ->
                            jdbc.update(
                                    "UPDATE tickets SET status = ?, closed_at = ? WHERE id = ?",
                                    status,
                                    closedAt,
                                    TICKET_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    static Stream<Arguments> closingTimes() {
        return STATUSES.stream()
                .flatMap(status -> {
                    boolean terminal = status.equals("RESOLVED") || status.equals("CANCELLED");
                    return Stream.of(
                            Arguments.of(status, null, !terminal),
                            Arguments.of(status, CREATED_AT.minusNanos(1000), false),
                            Arguments.of(status, CREATED_AT, terminal),
                            Arguments.of(status, CREATED_AT.plusSeconds(1), terminal));
                });
    }

    @ParameterizedTest
    @MethodSource("transitions")
    void permitsOnlyFourHistoryTransitions(String from, String to, boolean allowed) {
        insertTicket();
        if (allowed) {
            assertThat(insertHistory(TICKET_ID, from, to, CREATED_AT)).isEqualTo(1);
        } else {
            assertThatThrownBy(() -> insertHistory(TICKET_ID, from, to, CREATED_AT))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    static Stream<Arguments> transitions() {
        return STATUSES.stream()
                .flatMap(from ->
                        STATUSES.stream()
                                .map(to -> {
                                    boolean allowed =
                                            from.equals("NEW")
                                                            && (to.equals("IN_PROGRESS")
                                                                    || to.equals("CANCELLED"))
                                                    || from.equals("IN_PROGRESS")
                                                            && (to.equals("RESOLVED")
                                                                    || to.equals("CANCELLED"));
                                    return Arguments.of(from, to, allowed);
                                }));
    }

    @Test
    void rejectsHistoryForMissingTicket() {
        assertThatThrownBy(() -> insertHistory(TICKET_ID, "NEW", "IN_PROGRESS", CREATED_AT))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ticket_id", "from_status", "to_status", "changed_at"})
    void rejectsMissingHistoryFields(String column) {
        insertTicket();
        insertHistory(TICKET_ID, "NEW", "IN_PROGRESS", CREATED_AT);
        assertThatThrownBy(
                        () -> jdbc.update("UPDATE ticket_status_history SET " + column + " = NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void preventsDeletingCategoryWithTickets() {
        insertTicket();
        assertThatThrownBy(() -> jdbc.update("DELETE FROM categories WHERE id = ?", categoryId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void preventsDeletingTicketWithHistory() {
        insertTicket();
        insertHistory(TICKET_ID, "NEW", "IN_PROGRESS", CREATED_AT);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM tickets WHERE id = ?", TICKET_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Оборудование", "", "   "})
    void rejectsDuplicateOrBlankCategory(String name) {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO categories (name) VALUES (?)", name))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertTicket() {
        jdbc.update(
                "INSERT INTO tickets (id, title, category_id, created_at) VALUES (?, ?, ?, ?)",
                TICKET_ID,
                "Не работает принтер",
                categoryId(),
                CREATED_AT);
    }

    private long categoryId() {
        return jdbc.queryForObject(
                "SELECT id FROM categories WHERE name = ?", Long.class, "Оборудование");
    }

    private int insertHistory(UUID ticketId, String from, String to, OffsetDateTime changedAt) {
        return jdbc.update(
                "INSERT INTO ticket_status_history (ticket_id, from_status, to_status, changed_at) VALUES (?, ?, ?, ?)",
                ticketId,
                from,
                to,
                changedAt);
    }
}
