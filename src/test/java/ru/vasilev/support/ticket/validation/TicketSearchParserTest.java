package ru.vasilev.support.ticket.validation;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.vasilev.support.common.error.ApiException;
import ru.vasilev.support.ticket.entity.TicketPriority;
import ru.vasilev.support.ticket.entity.TicketStatus;

class TicketSearchParserTest {
    private final TicketSearchParser parser = new TicketSearchParser();

    @Test
    void appliesDefaultsWithoutFilters() {
        var request = parser.parse(null, null, null, null, null, null);
        assertThat(request.status()).isNull();
        assertThat(request.priority()).isNull();
        assertThat(request.categoryId()).isNull();
        assertThat(request.overdue()).isNull();
        assertThat(request.page()).isZero();
        assertThat(request.size()).isEqualTo(20);
    }

    @ParameterizedTest
    @CsvSource({"0,1,0", "0,100,0", "2147483647,100,214748364700"})
    void acceptsPaginationBoundariesWithoutOffsetOverflow(String page, String size, long offset) {
        var request =
                parser.parse("IN_PROGRESS", "HIGH", "9223372036854775807", "false", page, size);
        assertThat(request.page()).isEqualTo(Integer.parseInt(page));
        assertThat(request.size()).isEqualTo(Integer.parseInt(size));
        assertThat(request.offset()).isEqualTo(offset);
        assertThat(request.categoryId()).isEqualTo(Long.MAX_VALUE);
        assertThat(request.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(request.priority()).isEqualTo(TicketPriority.HIGH);
        assertThat(request.overdue()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "-1,20,page",
        "2147483648,20,page",
        "1.5,20,page",
        "1e2,20,page",
        "0,0,size",
        "0,101,size",
        "0,-1,size",
        "0,9223372036854775808,size"
    })
    void rejectsInvalidPagination(String page, String size, String field) {
        assertThatThrownBy(() -> parser.parse(null, null, null, null, page, size))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.getStatus().value()).isEqualTo(400);
                            assertThat(error.getViolations().getFirst().field()).isEqualTo(field);
                        });
    }
}
