package ru.vasilev.support.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.vasilev.support.ticket.dto.TicketResponse;
import ru.vasilev.support.ticket.dto.TicketSearchRequest;
import ru.vasilev.support.ticket.entity.Ticket;
import ru.vasilev.support.ticket.mapper.TicketMapper;
import ru.vasilev.support.ticket.repository.TicketSearchRepository;

class TicketSearchServiceTest {
    private final TicketSearchRepository repository = mock(TicketSearchRepository.class);
    private final TicketMapper mapper = mock(TicketMapper.class);
    private final Clock clock = mock(Clock.class);
    private final TicketSearchService service = new TicketSearchService(repository, mapper, clock);

    @Test
    void usesOneMicrosecondInstantForFilterAndEveryResponse() {
        var now = Instant.parse("2026-11-11T09:00:00.123456Z");
        when(clock.instant()).thenReturn(now.plusNanos(789), now.plusSeconds(1));
        var request = new TicketSearchRequest(null, null, null, true, 0, 20);
        var first = mock(Ticket.class);
        var second = mock(Ticket.class);
        var firstResponse = mock(TicketResponse.class);
        var secondResponse = mock(TicketResponse.class);
        when(repository.search(request, now))
                .thenReturn(new TicketSearchRepository.SearchResult(List.of(first, second), 2));
        when(mapper.toResponse(first, now)).thenReturn(firstResponse);
        when(mapper.toResponse(second, now)).thenReturn(secondResponse);
        assertThat(service.search(request).content())
                .containsExactly(firstResponse, secondResponse);
        verify(clock, times(1)).instant();
        verify(repository).search(request, now);
        verify(mapper).toResponse(first, now);
        verify(mapper).toResponse(second, now);
    }

    @ParameterizedTest
    @CsvSource({
        "0,20,0",
        "1,20,1",
        "20,20,1",
        "21,20,2",
        "9223372036854775807,100,92233720368547759"
    })
    void calculatesTotalPagesWithoutOverflow(long total, int size, long expectedPages) {
        var now = Instant.parse("2026-11-11T09:00:00Z");
        when(clock.instant()).thenReturn(now);
        var request = new TicketSearchRequest(null, null, null, null, Integer.MAX_VALUE, size);
        when(repository.search(request, now))
                .thenReturn(new TicketSearchRepository.SearchResult(List.of(), total));
        var result = service.search(request);
        assertThat(result.content()).isEmpty();
        assertThat(result.page()).isEqualTo(Integer.MAX_VALUE);
        assertThat(result.size()).isEqualTo(size);
        assertThat(result.totalElements()).isEqualTo(total);
        assertThat(result.totalPages()).isEqualTo(expectedPages);
    }
}
