package ru.vasilev.support.ticket.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.vasilev.support.common.error.ApiException;
import ru.vasilev.support.ticket.dto.ChangeTicketStatusRequest;
import ru.vasilev.support.ticket.dto.StatusHistoryResponse;
import ru.vasilev.support.ticket.dto.TicketResponse;
import ru.vasilev.support.ticket.entity.TicketStatusHistory;
import ru.vasilev.support.ticket.mapper.TicketMapper;
import ru.vasilev.support.ticket.repository.TicketRepository;
import ru.vasilev.support.ticket.repository.TicketStatusHistoryRepository;
import ru.vasilev.support.ticket.validation.TicketRequestValidator;

@Service
@Transactional(readOnly = true)
public class TicketWorkflowService {
    private final TicketRepository tickets;
    private final TicketStatusHistoryRepository history;
    private final TicketRequestValidator validator;
    private final TicketMapper mapper;
    private final Clock clock;

    public TicketWorkflowService(
            TicketRepository tickets,
            TicketStatusHistoryRepository history,
            TicketRequestValidator validator,
            TicketMapper mapper,
            Clock clock) {
        this.tickets = tickets;
        this.history = history;
        this.validator = validator;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public TicketResponse changeStatus(UUID id, ChangeTicketStatusRequest request) {
        validator.validate(request);
        var ticket = tickets.findByIdForUpdate(id).orElseThrow(TicketErrors::notFound);
        var from = ticket.getStatus();
        if (!TicketTransitionPolicy.isAllowed(from, request.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "STATE_CONFLICT",
                    "Переход в указанный статус запрещён.",
                    List.of());
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        ticket.changeStatus(request.status(), now);
        // Изменение заявки отправляется в БД до истории; обе записи остаются в одной транзакции.
        tickets.flush();
        history.save(new TicketStatusHistory(ticket, from, request.status(), now));
        return mapper.toResponse(ticket, now);
    }

    public List<StatusHistoryResponse> getHistory(UUID id) {
        if (!tickets.existsById(id)) {
            throw TicketErrors.notFound();
        }
        return history.findByTicketIdOrderByIdAsc(id).stream()
                .map(StatusHistoryResponse::from)
                .toList();
    }
}
