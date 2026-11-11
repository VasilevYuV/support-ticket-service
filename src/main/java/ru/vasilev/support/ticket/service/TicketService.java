package ru.vasilev.support.ticket.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.vasilev.support.category.service.CategoryService;
import ru.vasilev.support.common.error.ApiException;
import ru.vasilev.support.common.validation.TextValues;
import ru.vasilev.support.ticket.dto.CreateTicketRequest;
import ru.vasilev.support.ticket.dto.ReplaceTicketDetailsRequest;
import ru.vasilev.support.ticket.dto.TicketResponse;
import ru.vasilev.support.ticket.entity.Ticket;
import ru.vasilev.support.ticket.entity.TicketPriority;
import ru.vasilev.support.ticket.entity.TicketStatus;
import ru.vasilev.support.ticket.mapper.TicketMapper;
import ru.vasilev.support.ticket.repository.TicketRepository;
import ru.vasilev.support.ticket.validation.TicketRequestValidator;

@Service
@Transactional(readOnly = true)
public class TicketService {
    private final TicketRepository repository;
    private final CategoryService categoryService;
    private final TicketRequestValidator validator;
    private final TicketMapper mapper;
    private final Clock clock;

    public TicketService(
            TicketRepository repository,
            CategoryService categoryService,
            TicketRequestValidator validator,
            TicketMapper mapper,
            Clock clock) {
        this.repository = repository;
        this.categoryService = categoryService;
        this.validator = validator;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public TicketResponse create(CreateTicketRequest request) {
        validator.validate(request);
        var category = categoryService.getRequired(request.categoryId());
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var ticket =
                new Ticket(
                        TextValues.stripWhitespace(request.title()),
                        request.description() == null ? "" : request.description(),
                        category,
                        request.priority() == null ? TicketPriority.MEDIUM : request.priority(),
                        now,
                        request.dueAt());
        return mapper.toResponse(repository.save(ticket), now);
    }

    public TicketResponse getById(UUID id) {
        var ticket = repository.findById(id).orElseThrow(this::notFound);
        return mapper.toResponse(ticket, clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    @Transactional
    public TicketResponse replaceDetails(UUID id, ReplaceTicketDetailsRequest request) {
        validator.validate(request);
        var ticket = repository.findByIdForUpdate(id).orElseThrow(this::notFound);
        if (ticket.getStatus() != TicketStatus.NEW
                && ticket.getStatus() != TicketStatus.IN_PROGRESS) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "STATE_CONFLICT",
                    "Завершённую или отменённую заявку нельзя редактировать.",
                    List.of());
        }
        ticket.replaceDetails(request.description(), request.priority(), request.dueAt());
        return mapper.toResponse(ticket, clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    private ApiException notFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "Заявка не найдена.", List.of());
    }
}
