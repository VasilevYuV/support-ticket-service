package ru.vasilev.support.report.validation;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.vasilev.support.common.error.ApiException;

class ReportPeriodParserTest {
    private final ReportPeriodParser parser = new ReportPeriodParser();

    @Test
    void normalizesOffsetAndPreservesMicroseconds() {
        var period =
                parser.parse("2026-11-01T03:00:00.123456+03:00", "2026-11-02T00:00:00.123457Z");
        assertThat(period.from()).isEqualTo(Instant.parse("2026-11-01T00:00:00.123456Z"));
        assertThat(period.to()).isEqualTo(Instant.parse("2026-11-02T00:00:00.123457Z"));
    }

    @ParameterizedTest
    @CsvSource(
            value = {
                "null,null,from",
                "null,2026-11-02T00:00:00Z,from",
                "2026-11-01T00:00:00Z,null,to"
            },
            nullValues = "null")
    void requiresBothBoundaries(String from, String to, String field) {
        assertThatThrownBy(() -> parser.parse(from, to))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.getStatus().value()).isEqualTo(400);
                            assertThat(error.getViolations().getFirst().field()).isEqualTo(field);
                        });
    }

    @ParameterizedTest
    @CsvSource({
        "2026-11-01T00:00:00,2026-11-02T00:00:00Z,from",
        "2026-11-01T00:00:00Z,2026-11-02T00:00:00.1234560Z,to"
    })
    void rejectsInvalidDateBeforeCallingService(String from, String to, String field) {
        assertThatThrownBy(() -> parser.parse(from, to))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.getCode()).isEqualTo("INVALID_REQUEST");
                            assertThat(error.getViolations().getFirst().field()).isEqualTo(field);
                        });
    }
}
