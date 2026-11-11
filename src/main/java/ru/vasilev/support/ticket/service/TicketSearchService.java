package ru.vasilev.support.ticket.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.vasilev.support.ticket.dto.TicketPageResponse;
import ru.vasilev.support.ticket.dto.TicketSearchRequest;
import ru.vasilev.support.ticket.mapper.TicketMapper;
import ru.vasilev.support.ticket.repository.TicketSearchRepository;

@Service
public class TicketSearchService {
    private final TicketSearchRepository repository;
    private final TicketMapper mapper;
    private final Clock clock;

    public TicketSearchService(
            TicketSearchRepository repository, TicketMapper mapper, Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TicketPageResponse search(TicketSearchRequest request) {
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var result = repository.search(request, now);
        var content =
                result.content().stream().map(ticket -> mapper.toResponse(ticket, now)).toList();
        long total = result.totalElements();
        long totalPages = total / request.size() + (total % request.size() == 0 ? 0 : 1);
        return new TicketPageResponse(content, request.page(), request.size(), total, totalPages);
    }
}
