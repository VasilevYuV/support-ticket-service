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
import ru.vasilev.support.ticket.dto.CreateTicketRequest;
import ru.vasilev.support.ticket.dto.TicketResponse;
import ru.vasilev.support.ticket.entity.Ticket;
import ru.vasilev.support.ticket.entity.TicketPriority;
import ru.vasilev.support.ticket.mapper.TicketMapper;
import ru.vasilev.support.ticket.repository.TicketRepository;
import ru.vasilev.support.ticket.validation.CreateTicketValidator;

@Service
@Transactional(readOnly = true)
public class TicketService {
    private final TicketRepository repository;
    private final CategoryService categoryService;
    private final CreateTicketValidator validator;
    private final TicketMapper mapper;
    private final Clock clock;

    public TicketService(
            TicketRepository repository,
            CategoryService categoryService,
            CreateTicketValidator validator,
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
                        request.title().strip(),
                        request.description() == null ? "" : request.description(),
                        category,
                        request.priority() == null ? TicketPriority.MEDIUM : request.priority(),
                        now,
                        request.dueAt());
        return mapper.toResponse(repository.save(ticket), now);
    }

    public TicketResponse getById(UUID id) {
        var ticket =
                repository
                        .findById(id)
                        .orElseThrow(() ->
                                new ApiException(
                                        HttpStatus.NOT_FOUND,
                                        "TICKET_NOT_FOUND",
                                        "Заявка не найдена.",
                                        List.of()));
        return mapper.toResponse(ticket, clock.instant().truncatedTo(ChronoUnit.MICROS));
    }
}
