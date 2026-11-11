package ru.vasilev.support.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManagerFactory;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.vasilev.support.config.ApiTestSupport;
import tools.jackson.databind.JsonNode;

@TestPropertySource(
        properties = {
            "spring.jpa.properties.hibernate.generate_statistics=true",
            "logging.level.org.hibernate.stat=OFF"
        })
class SummaryReportApiTest extends ApiTestSupport {
    private static final String PATH = "/api/reports/summary";
    private static final Instant FROM = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-02T00:00:00Z");
    private static final Instant REPORT_NOW = Instant.parse("2026-11-03T00:00:00Z");

    @MockitoBean(name = "fixedClock")
    private Clock reportClock;

    @Autowired private EntityManagerFactory entityManagerFactory;
    private Statistics statistics;

    @BeforeEach
    void prepareReportClock() {
        when(reportClock.instant()).thenReturn(REPORT_NOW.plusNanos(789));
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    @Override
    protected Instant expectedNow() {
        return REPORT_NOW;
    }

    @Test
    void calculatesControlDatasetThroughJavaAndKotlinWithOneQuery() throws Exception {
        seedControlDataset();
        var before = jdbc.queryForList("SELECT * FROM tickets ORDER BY id");
        var history = jdbc.queryForList("SELECT * FROM ticket_status_history ORDER BY id");
        var response = send("GET", period(FROM.toString(), TO.toString()), null);
        assertThat(response.status()).isEqualTo(200);
        var report = response.body();
        assertReport(report, FROM, TO, 6, 1);
        assertCounts(
                report.get("byStatus"), "NEW", 2, "IN_PROGRESS", 1, "RESOLVED", 2, "CANCELLED", 1);
        assertCounts(report.get("byPriority"), "LOW", 2, "MEDIUM", 2, "HIGH", 2);
        assertThat(report.get("averageResolutionSeconds").isNumber()).isTrue();
        assertThat(report.get("averageResolutionSeconds").decimalValue())
                .isEqualByComparingTo("7200");
        assertOneQuery();
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM tickets ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM ticket_status_history ORDER BY id"))
                .isEqualTo(history);
    }

    @Test
    void returnsExplicitNullAndAllZeroGroupsForEmptySelection() throws Exception {
        add(1, "RESOLVED", "HIGH", TO, null, TO.plusSeconds(3600));
        var response = send("GET", period(FROM.toString(), TO.toString()), null);
        assertThat(response.status()).isEqualTo(200);
        var report = response.body();
        assertReport(report, FROM, TO, 0, 0);
        assertCounts(
                report.get("byStatus"), "NEW", 0, "IN_PROGRESS", 0, "RESOLVED", 0, "CANCELLED", 0);
        assertCounts(report.get("byPriority"), "LOW", 0, "MEDIUM", 0, "HIGH", 0);
        assertThat(report.get("averageResolutionSeconds").isNull()).isTrue();
        assertOneQuery();
    }

    @Test
    void excludesCancelledAndOpenTicketsFromAverage() throws Exception {
        add(1, "NEW", "HIGH", FROM, REPORT_NOW.minusNanos(1000), null);
        add(2, "IN_PROGRESS", "MEDIUM", FROM.plusSeconds(1), null, null);
        add(
                3,
                "CANCELLED",
                "LOW",
                FROM.plusSeconds(2),
                REPORT_NOW.minusSeconds(1),
                TO.plusSeconds(3600));
        var response = send("GET", period(FROM.toString(), TO.toString()), null);
        assertThat(response.status()).isEqualTo(200);
        assertReport(response.body(), FROM, TO, 3, 1);
        assertCounts(
                response.body().get("byStatus"),
                "NEW",
                1,
                "IN_PROGRESS",
                1,
                "RESOLVED",
                0,
                "CANCELLED",
                1);
        assertCounts(response.body().get("byPriority"), "LOW", 1, "MEDIUM", 1, "HIGH", 1);
        assertThat(response.body().get("averageResolutionSeconds").isNull()).isTrue();
        assertOneQuery();
    }

    @Test
    void preservesMicrosecondPeriodBoundaries() throws Exception {
        var from = FROM.plusNanos(123456000);
        var to = from.plusNanos(1000);
        add(1, "NEW", "LOW", from.minusNanos(1000), null, null);
        add(2, "NEW", "LOW", from, null, null);
        add(3, "NEW", "LOW", to, null, null);
        var response = send("GET", period(from.toString(), to.toString()), null);
        assertThat(response.status()).isEqualTo(200);
        assertReport(response.body(), from, to, 1, 0);
        assertOneQuery();
    }

    @Test
    void acceptsEncodedOffsetsAndReturnsUtcBoundaries() throws Exception {
        seedControlDataset();
        var utc = send("GET", period(FROM.toString(), TO.toString()), null);
        var shifted =
                send("GET", period("2026-11-01T03:00:00+03:00", "2026-11-01T21:00:00-03:00"), null);
        assertThat(utc.status()).isEqualTo(200);
        assertThat(shifted.status()).isEqualTo(200);
        assertThat(shifted.body()).isEqualTo(utc.body());
    }

    @ParameterizedTest
    @CsvSource({"'',from", "?from=2026-11-01T00:00:00Z,to", "?to=2026-11-02T00:00:00Z,from"})
    void requiresBothBoundariesBeforeDatabaseAccess(String query, String field) throws Exception {
        var response = send("GET", PATH + query, null);
        assertError(response, 400, "VALIDATION_ERROR", PATH);
        assertField(response, field);
        assertNoQuery();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"2026-11-01T00:00:00Z", "2026-10-31T23:59:59Z", "2026-11-01T03:00:00+03:00"})
    void rejectsEqualOrReversedPeriodsBeforeDatabaseAccess(String to) throws Exception {
        var response = send("GET", period(FROM.toString(), to), null);
        assertError(response, 400, "VALIDATION_ERROR", PATH);
        assertField(response, "period");
        assertNoQuery();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2026-11-01T00:00:00",
                "2026-11-01",
                "2026-02-30T00:00:00Z",
                "2026-11-01T00:00:00.1234560Z",
                "0000-01-01T00:00:00Z",
                "0001-01-01T00:00:00+01:00",
                "2026-11-01T24:00:00Z",
                "2026-11-01T00:00:60Z",
                "null",
                "2026-11-01T00:00:00Z "
            })
    void rejectsMalformedDatesInEitherBoundary(String value) throws Exception {
        for (String field : new String[] {"from", "to"}) {
            var path =
                    field.equals("from")
                            ? period(value, TO.toString())
                            : period(FROM.toString(), value);
            var response = send("GET", path, null);
            assertError(response, 400, "INVALID_REQUEST", PATH);
            assertField(response, field);
        }
        assertNoQuery();
    }

    @ParameterizedTest
    @ValueSource(strings = {"from", "to"})
    void rejectsEmptyAndRepeatedBoundaries(String field) throws Exception {
        String other = field.equals("from") ? "to=" + TO : "from=" + FROM;
        for (String value : new String[] {"", "=", "=" + FROM + "&" + field + "=" + TO}) {
            var response = send("GET", PATH + "?" + other + "&" + field + value, null);
            assertError(response, 400, "INVALID_REQUEST", PATH);
            assertField(response, field);
        }
        assertNoQuery();
    }

    @Test
    void rejectsUnknownParametersAndUnescapedPlus() throws Exception {
        var unknown = send("GET", period(FROM.toString(), TO.toString()) + "&page=0", null);
        assertError(unknown, 400, "INVALID_REQUEST", PATH);
        assertField(unknown, "page");
        var unescaped = send("GET", PATH + "?from=2026-11-01T03:00:00+03:00&to=" + TO, null);
        assertError(unescaped, 400, "INVALID_REQUEST", PATH);
        assertField(unescaped, "from");
        assertNoQuery();
    }

    @Test
    void returnsUniformMethodAndAcceptErrors() throws Exception {
        var method = send("POST", period(FROM.toString(), TO.toString()), null);
        assertError(method, 405, "METHOD_NOT_ALLOWED", PATH);
        assertThat(method.headers().firstValue("Allow"))
                .hasValueSatisfying(value -> assertThat(value).contains("GET"));
        var request =
                HttpRequest.newBuilder(uri(period(FROM.toString(), TO.toString())))
                        .header("Accept", "text/plain")
                        .GET()
                        .build();
        assertError(send(request), 406, "NOT_ACCEPTABLE", PATH);
    }

    private String period(String from, String to) {
        return PATH
                + "?from="
                + URLEncoder.encode(from, StandardCharsets.UTF_8)
                + "&to="
                + URLEncoder.encode(to, StandardCharsets.UTF_8);
    }

    private void assertReport(JsonNode report, Instant from, Instant to, long total, long overdue) {
        assertThat(report.size()).isEqualTo(7);
        assertThat(report.get("from").stringValue()).isEqualTo(from.toString());
        assertThat(report.get("to").stringValue()).isEqualTo(to.toString());
        assertThat(report.get("totalCreated").longValue()).isEqualTo(total);
        assertThat(report.get("openOverdue").longValue()).isEqualTo(overdue);
        long statusTotal = 0;
        for (var value : report.get("byStatus")) {
            statusTotal += value.longValue();
        }
        long priorityTotal = 0;
        for (var value : report.get("byPriority")) {
            priorityTotal += value.longValue();
        }
        assertThat(statusTotal).isEqualTo(total);
        assertThat(priorityTotal).isEqualTo(total);
    }

    private void assertCounts(JsonNode counts, Object... fieldsAndValues) {
        assertThat(counts.size()).isEqualTo(fieldsAndValues.length / 2);
        for (int index = 0; index < fieldsAndValues.length; index += 2) {
            assertThat(counts.get((String) fieldsAndValues[index]).longValue())
                    .isEqualTo(((Number) fieldsAndValues[index + 1]).longValue());
        }
    }

    private void assertField(ApiResult result, String field) {
        assertThat(result.body().get("violations").get(0).get("field").stringValue())
                .isEqualTo(field);
    }

    private void assertOneQuery() {
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    private void assertNoQuery() {
        assertThat(statistics.getQueryExecutionCount()).isZero();
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    private void seedControlDataset() {
        add(1, "NEW", "HIGH", FROM, REPORT_NOW.minusNanos(1000), null);
        add(2, "IN_PROGRESS", "MEDIUM", FROM.plusSeconds(3600), REPORT_NOW, null);
        add(
                3,
                "RESOLVED",
                "LOW",
                FROM.plusSeconds(7200),
                FROM.plusSeconds(10800),
                FROM.plusSeconds(10800));
        add(4, "RESOLVED", "HIGH", FROM.plusSeconds(82800), null, TO.plusSeconds(7200));
        add(
                5,
                "CANCELLED",
                "LOW",
                FROM.plusSeconds(10800),
                FROM.plusSeconds(14400),
                FROM.plusSeconds(14400));
        add(6, "RESOLVED", "HIGH", TO, null, TO.plusSeconds(3600));
        add(7, "NEW", "LOW", FROM.minusNanos(1000), FROM.plusSeconds(3600), null);
        add(8, "NEW", "MEDIUM", FROM.plusSeconds(14400), null, null);
    }

    private void add(
            int number,
            String status,
            String priority,
            Instant createdAt,
            Instant dueAt,
            Instant closedAt) {
        var id = new UUID(0, number);
        jdbc.update(
                "INSERT INTO tickets (id, title, category_id, status, priority, created_at, due_at, closed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                "Заявка " + number,
                categoryId,
                status,
                priority,
                createdAt.atOffset(ZoneOffset.UTC),
                dueAt == null ? null : dueAt.atOffset(ZoneOffset.UTC),
                closedAt == null ? null : closedAt.atOffset(ZoneOffset.UTC));
        if (status.equals("IN_PROGRESS") || status.equals("RESOLVED")) {
            jdbc.update(
                    "INSERT INTO ticket_status_history (ticket_id, from_status, to_status, changed_at) VALUES (?, 'NEW', 'IN_PROGRESS', ?)",
                    id,
                    createdAt.atOffset(ZoneOffset.UTC));
        }
        if (closedAt != null) {
            jdbc.update(
                    "INSERT INTO ticket_status_history (ticket_id, from_status, to_status, changed_at) VALUES (?, ?, ?, ?)",
                    id,
                    status.equals("RESOLVED") ? "IN_PROGRESS" : "NEW",
                    status,
                    closedAt.atOffset(ZoneOffset.UTC));
        }
    }
}
