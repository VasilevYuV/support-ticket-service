package ru.vasilev.support.ticket.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.vasilev.support.config.ApiTestSupport;
import tools.jackson.databind.JsonNode;

class TicketSearchApiTest extends ApiTestSupport {
    private static final String PATH = "/api/tickets";
    private final List<Fixture> fixtures = new ArrayList<>();
    private long otherCategoryId;

    @BeforeEach
    void seedTickets() {
        otherCategoryId =
                jdbc.queryForObject("SELECT id FROM categories WHERE name = 'Сеть'", Long.class);
        add(
                1,
                "00000000-0000-0000-0000-000000000001",
                "NEW",
                "HIGH",
                categoryId,
                -60,
                NOW.minusNanos(1000));
        add(
                2,
                "00000000-0000-0000-0000-000000000002",
                "IN_PROGRESS",
                "HIGH",
                categoryId,
                -60,
                NOW.minusSeconds(1));
        add(3, "00000000-0000-0000-0000-000000000003", "NEW", "LOW", otherCategoryId, -60, NOW);
        add(
                4,
                "00000000-0000-0000-0000-000000000004",
                "IN_PROGRESS",
                "MEDIUM",
                otherCategoryId,
                -60,
                NOW.plusNanos(1000));
        add(5, "00000000-0000-0000-0000-000000000005", "NEW", "MEDIUM", categoryId, -60, null);
        add(
                6,
                "00000000-0000-0000-0000-000000000006",
                "RESOLVED",
                "HIGH",
                categoryId,
                -120,
                NOW.minusSeconds(1));
        add(
                7,
                "7fffffff-ffff-ffff-ffff-ffffffffffff",
                "CANCELLED",
                "LOW",
                otherCategoryId,
                -60,
                NOW.minusSeconds(1));
        add(
                8,
                "80000000-0000-0000-0000-000000000000",
                "RESOLVED",
                "MEDIUM",
                otherCategoryId,
                -60,
                null);
        add(
                9,
                "ffffffff-ffff-ffff-ffff-ffffffffffff",
                "CANCELLED",
                "HIGH",
                categoryId,
                -60,
                NOW.plusSeconds(1));
        add(
                10,
                "00000000-0000-0000-0000-000000000010",
                "IN_PROGRESS",
                "LOW",
                categoryId,
                -10,
                null);
    }

    @ParameterizedTest
    @MethodSource("filters")
    void filtersByEveryConditionAndTheirIntersection(String query, List<Integer> numbers)
            throws Exception {
        var before = jdbc.queryForList("SELECT * FROM tickets ORDER BY id");
        var history = jdbc.queryForList("SELECT * FROM ticket_status_history ORDER BY id");
        query =
                query.replace("{category}", Long.toString(categoryId))
                        .replace("{other}", Long.toString(otherCategoryId));
        var response = send("GET", PATH + "?" + query, null);
        assertPage(response, 0, 20, numbers.size());
        var expected = ordered().stream().filter(row -> numbers.contains(row.number())).toList();
        assertRows(response.body().get("content"), expected);
        assertThat(jdbc.queryForList("SELECT * FROM tickets ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM ticket_status_history ORDER BY id"))
                .isEqualTo(history);
    }

    static Stream<Arguments> filters() {
        return Stream.of(
                Arguments.of("status=NEW", List.of(1, 3, 5)),
                Arguments.of("status=IN_PROGRESS", List.of(2, 4, 10)),
                Arguments.of("status=RESOLVED", List.of(6, 8)),
                Arguments.of("status=CANCELLED", List.of(7, 9)),
                Arguments.of("priority=HIGH", List.of(1, 2, 6, 9)),
                Arguments.of("priority=MEDIUM", List.of(4, 5, 8)),
                Arguments.of("priority=LOW", List.of(3, 7, 10)),
                Arguments.of("categoryId={category}", List.of(1, 2, 5, 6, 9, 10)),
                Arguments.of("categoryId={other}", List.of(3, 4, 7, 8)),
                Arguments.of("categoryId=9223372036854775807", List.of()),
                Arguments.of("overdue=true", List.of(1, 2)),
                Arguments.of("overdue=false", List.of(3, 4, 5, 6, 7, 8, 9, 10)),
                Arguments.of(
                        "status=NEW&priority=HIGH&categoryId={category}&overdue=true", List.of(1)),
                Arguments.of("status=NEW&priority=HIGH&categoryId={other}&overdue=true", List.of()),
                Arguments.of("status=RESOLVED&overdue=true", List.of()),
                Arguments.of("status=RESOLVED&overdue=false", List.of(6, 8)),
                Arguments.of("priority=LOW&categoryId={category}&overdue=false", List.of(10)));
    }

    @Test
    void pagesInStableUnsignedUuidOrderWithoutGapsOrDuplicates() throws Exception {
        var expected = ordered();
        assertThat(expected.stream().map(Fixture::number))
                .containsExactly(10, 9, 8, 7, 5, 4, 3, 2, 1, 6);
        var seen = new ArrayList<String>();
        for (int page = 0; page < 4; page++) {
            var response = send("GET", PATH + "?page=" + page + "&size=3", null);
            assertPage(response, page, 3, 10);
            var rows = response.body().get("content");
            assertRows(rows, expected.subList(page * 3, Math.min(page * 3 + 3, 10)));
            for (var row : rows) {
                seen.add(row.get("id").stringValue());
            }
            assertThat(send("GET", PATH + "?page=" + page + "&size=3", null).body())
                    .isEqualTo(response.body());
        }
        assertThat(seen)
                .doesNotHaveDuplicates()
                .containsExactlyElementsOf(
                        expected.stream().map(row -> row.id().toString()).toList());
    }

    @Test
    void usesDefaultsAndLimitsContentToTwentyRows() throws Exception {
        for (int index = 11; index <= 25; index++) {
            add(
                    index,
                    "00000000-0000-0000-0000-%012d".formatted(index),
                    "NEW",
                    "LOW",
                    categoryId,
                    -60,
                    null);
        }
        var response = send("GET", PATH, null);
        assertPage(response, 0, 20, 25);
        assertRows(response.body().get("content"), ordered().subList(0, 20));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void acceptsSizeBoundsAndHugePage(int size) throws Exception {
        var first = send("GET", PATH + "?size=" + size, null);
        assertPage(first, 0, size, 10);
        assertRows(first.body().get("content"), ordered().subList(0, Math.min(size, 10)));
        var beyond = send("GET", PATH + "?page=2147483647&size=" + size, null);
        assertPage(beyond, Integer.MAX_VALUE, size, 10);
        assertThat(beyond.body().get("content").size()).isZero();
    }

    @Test
    void countsOnlyMatchingRowsOnEveryPageIncludingPagePastEnd() throws Exception {
        var expected = ordered().stream().filter(row -> row.status().equals("NEW")).toList();
        for (int page = 0; page <= 2; page++) {
            var response = send("GET", PATH + "?status=NEW&page=" + page + "&size=2", null);
            assertPage(response, page, 2, 3);
            int start = Math.min(page * 2, 3);
            assertRows(
                    response.body().get("content"),
                    expected.subList(start, Math.min(start + 2, 3)));
        }
    }

    @Test
    void returnsEmptyPageForEmptyDatabase() throws Exception {
        jdbc.update("DELETE FROM ticket_status_history");
        jdbc.update("DELETE FROM tickets");
        var response = send("GET", PATH, null);
        assertPage(response, 0, 20, 0);
        assertThat(response.body().get("content").size()).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "page=-1",
                "page=2147483648",
                "page=1.0",
                "page=1e2",
                "page=%2B1",
                "page=%201",
                "page=one",
                "size=0",
                "size=101",
                "size=-1",
                "size=1.5",
                "categoryId=0",
                "categoryId=-1",
                "categoryId=9223372036854775808",
                "categoryId=1.0",
                "categoryId=1e0",
                "categoryId=1%20OR%201=1",
                "status=new",
                "status=CLOSED",
                "status=%20NEW",
                "status=NEW,IN_PROGRESS",
                "priority=high",
                "priority=1",
                "overdue=TRUE",
                "overdue=False",
                "overdue=1",
                "overdue=yes",
                "overdue=null",
                "sort=createdAt",
                "extra=1"
            })
    void rejectsInvalidParametersWithoutChanges(String query) throws Exception {
        var before = jdbc.queryForList("SELECT * FROM tickets ORDER BY id");
        var response = send("GET", PATH + "?" + query, null);
        assertThat(response.body().get("code").stringValue())
                .isIn("INVALID_REQUEST", "VALIDATION_ERROR");
        assertError(response, 400, response.body().get("code").stringValue(), PATH);
        assertThat(response.body().get("violations").get(0).get("field").stringValue())
                .isEqualTo(query.split("=")[0]);
        assertThat(jdbc.queryForList("SELECT * FROM tickets ORDER BY id")).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "priority", "categoryId", "overdue", "page", "size"})
    void rejectsEmptyMissingAndRepeatedValues(String name) throws Exception {
        for (String suffix : new String[] {"", "=", "=1&" + name + "=1"}) {
            var response = send("GET", PATH + "?" + name + suffix, null);
            assertError(response, 400, "INVALID_REQUEST", PATH);
            assertThat(response.body().get("violations").get(0).get("field").stringValue())
                    .isEqualTo(name);
        }
    }

    @Test
    void doesNotEnableSearchParametersOnOtherRoutes() throws Exception {
        var response =
                send(
                        "POST",
                        PATH + "?page=0",
                        "{\"title\":\"Заявка\",\"categoryId\":" + categoryId + "}");
        assertError(response, 400, "INVALID_REQUEST", PATH);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tickets", Integer.class))
                .isEqualTo(10);
    }

    private void assertPage(ApiResult response, int page, int size, long total) {
        assertThat(response.status()).isEqualTo(200);
        var body = response.body();
        assertThat(body.size()).isEqualTo(5);
        assertThat(body.get("content").isArray()).isTrue();
        assertThat(body.get("page").intValue()).isEqualTo(page);
        assertThat(body.get("size").intValue()).isEqualTo(size);
        assertThat(body.get("totalElements").longValue()).isEqualTo(total);
        assertThat(body.get("totalPages").longValue()).isEqualTo((total + size - 1) / size);
    }

    private void assertRows(JsonNode rows, List<Fixture> expected) {
        assertThat(rows.size()).isEqualTo(expected.size());
        for (int index = 0; index < expected.size(); index++) {
            var actual = rows.get(index);
            var fixture = expected.get(index);
            assertThat(actual.size()).isEqualTo(10);
            assertThat(actual.get("id").stringValue()).isEqualTo(fixture.id().toString());
            assertThat(actual.get("title").stringValue()).isEqualTo("Заявка " + fixture.number());
            assertThat(actual.get("description").stringValue()).isEqualTo("Описание");
            assertThat(actual.get("status").stringValue()).isEqualTo(fixture.status());
            assertThat(actual.get("priority").stringValue()).isEqualTo(fixture.priority());
            assertThat(actual.get("category").get("id").longValue())
                    .isEqualTo(fixture.categoryId());
            assertThat(actual.get("category").get("name").stringValue())
                    .isEqualTo(fixture.categoryId() == categoryId ? "Оборудование" : "Сеть");
            assertThat(Instant.parse(actual.get("createdAt").stringValue()))
                    .isEqualTo(fixture.createdAt());
            if (fixture.dueAt() == null) {
                assertThat(actual.get("dueAt").isNull()).isTrue();
            } else {
                assertThat(Instant.parse(actual.get("dueAt").stringValue()))
                        .isEqualTo(fixture.dueAt());
            }
            boolean terminal =
                    fixture.status().equals("RESOLVED") || fixture.status().equals("CANCELLED");
            if (terminal) {
                assertThat(Instant.parse(actual.get("closedAt").stringValue())).isEqualTo(NOW);
            } else {
                assertThat(actual.get("closedAt").isNull()).isTrue();
            }
            assertThat(actual.get("overdue").booleanValue())
                    .isEqualTo(
                            !terminal && fixture.dueAt() != null && fixture.dueAt().isBefore(NOW));
        }
    }

    private List<Fixture> ordered() {
        return fixtures.stream()
                .sorted(
                        Comparator.comparing(Fixture::createdAt)
                                .reversed()
                                .thenComparing(
                                        row -> row.id().toString(), Comparator.reverseOrder()))
                .toList();
    }

    private void add(
            int number,
            String uuid,
            String status,
            String priority,
            long category,
            int createdOffset,
            Instant dueAt) {
        var id = UUID.fromString(uuid);
        var createdAt = NOW.plusSeconds(createdOffset);
        boolean terminal = status.equals("RESOLVED") || status.equals("CANCELLED");
        jdbc.update(
                "INSERT INTO tickets (id, title, description, category_id, priority, status, created_at, due_at, closed_at) VALUES (?, ?, 'Описание', ?, ?, ?, ?, ?, ?)",
                id,
                "Заявка " + number,
                category,
                priority,
                status,
                time(createdAt),
                time(dueAt),
                terminal ? time(NOW) : null);
        if (status.equals("IN_PROGRESS") || status.equals("RESOLVED")) {
            jdbc.update(
                    "INSERT INTO ticket_status_history (ticket_id, from_status, to_status, changed_at) VALUES (?, 'NEW', 'IN_PROGRESS', ?)",
                    id,
                    time(createdAt));
        }
        if (terminal) {
            jdbc.update(
                    "INSERT INTO ticket_status_history (ticket_id, from_status, to_status, changed_at) VALUES (?, ?, ?, ?)",
                    id,
                    status.equals("RESOLVED") ? "IN_PROGRESS" : "NEW",
                    status,
                    time(NOW));
        }
        fixtures.add(new Fixture(number, id, status, priority, category, createdAt, dueAt));
    }

    private OffsetDateTime time(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private record Fixture(
            int number,
            UUID id,
            String status,
            String priority,
            long categoryId,
            Instant createdAt,
            Instant dueAt) {}
}
