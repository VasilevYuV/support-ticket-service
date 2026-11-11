package ru.vasilev.support.ticket.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import ru.vasilev.support.category.entity.Category;
import ru.vasilev.support.category.repository.CategoryRepository;
import ru.vasilev.support.category.service.CategoryService;
import ru.vasilev.support.common.error.ApiException;
import ru.vasilev.support.ticket.dto.CreateTicketRequest;
import ru.vasilev.support.ticket.entity.Ticket;
import ru.vasilev.support.ticket.entity.TicketPriority;
import ru.vasilev.support.ticket.entity.TicketStatus;
import ru.vasilev.support.ticket.mapper.TicketMapper;
import ru.vasilev.support.ticket.repository.TicketRepository;
import ru.vasilev.support.ticket.validation.TicketRequestValidator;
import tools.jackson.databind.json.JsonMapper;

class TicketCreationServiceTest {
    private static final Instant NOW = Instant.parse("2035-12-31T23:59:59.123456Z");
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final Clock clock = mock(Clock.class);
    private TicketService service;

    @BeforeEach
    void setUp() {
        service =
                new TicketService(
                        tickets,
                        new CategoryService(categories),
                        new TicketRequestValidator(),
                        new TicketMapper(),
                        clock);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"title\":\"Заявка\",\"categoryId\":1}",
                "{\"title\":\"Заявка\",\"categoryId\":1,\"description\":null,\"priority\":null,\"dueAt\":null}"
            })
    void appliesDefaultsForMissingAndNullOptionalFields(String body) {
        var category = prepareCategoryAndSave();
        var request = JsonMapper.builder().build().readValue(body, CreateTicketRequest.class);

        var result = service.create(request);

        var saved = ArgumentCaptor.forClass(Ticket.class);
        verify(tickets).save(saved.capture());
        assertThat(saved.getValue().getCategory()).isSameAs(category);
        assertThat(saved.getValue().getStatus()).isEqualTo(TicketStatus.NEW);
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getDescription()).isEmpty();
        assertThat(saved.getValue().getPriority()).isEqualTo(TicketPriority.MEDIUM);
        assertThat(saved.getValue().getDueAt()).isNull();
        assertThat(saved.getValue().getClosedAt()).isNull();
        assertThat(result.category().id()).isEqualTo(1L);
        assertThat(result.status()).isEqualTo(TicketStatus.NEW);
        assertThat(result.createdAt()).isEqualTo(NOW);
        assertThat(result.description()).isEmpty();
        assertThat(result.priority()).isEqualTo(TicketPriority.MEDIUM);
        assertThat(result.dueAt()).isNull();
        assertThat(result.closedAt()).isNull();
        assertThat(result.overdue()).isFalse();
        verify(clock).instant();
    }

    @ParameterizedTest
    @EnumSource(TicketPriority.class)
    void preservesExplicitPriorityDescriptionAndDueDate(TicketPriority priority) {
        prepareCategoryAndSave();
        var dueAt = NOW.minusSeconds(1);
        var description = "  Описание\nс пробелами  ";

        var result =
                service.create(new CreateTicketRequest("Заявка", 1L, description, priority, dueAt));

        assertThat(result.priority()).isEqualTo(priority);
        assertThat(result.description()).isEqualTo(description);
        assertThat(result.dueAt()).isEqualTo(dueAt);
        assertThat(result.overdue()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\t \r\n", "\u00a0\u2003\u202f\u3000"})
    void trimsOnlyTitleEdgesAndKeepsInternalWhitespace(String edges) {
        prepareCategoryAndSave();
        var title = "Принтер  😀\u00a0не работает";

        var result =
                service.create(
                        new CreateTicketRequest(edges + title + edges, 1L, null, null, null));

        assertThat(result.title()).isEqualTo(title);
        var saved = ArgumentCaptor.forClass(Ticket.class);
        verify(tickets).save(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo(title);
    }

    @Test
    void usesOneMicrosecondClockValueForCreationAndOverdue() {
        prepareCategoryAndSave();
        var result = service.create(new CreateTicketRequest("Заявка", 1L, null, null, NOW));
        assertThat(result.createdAt()).isEqualTo(NOW);
        assertThat(result.overdue()).isFalse();
        verify(clock).instant();
    }

    @Test
    void rejectsUnknownCategoryWithoutReadingClockOrSavingTicket() {
        when(categories.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                        service.create(new CreateTicketRequest("Заявка", 7L, null, null, null)))
                .isInstanceOfSatisfying(
                        ApiException.class, error -> assertField(error, "categoryId"));

        verify(categories).findById(7L);
        verifyNoInteractions(tickets, clock);
    }

    @Test
    void validatesBeforeLookingUpCategoryOrSavingTicket() {
        assertThatThrownBy(() ->
                        service.create(new CreateTicketRequest("\u00a0", 1L, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, error -> assertField(error, "title"));
        verifyNoInteractions(categories, tickets, clock);
    }

    private Category prepareCategoryAndSave() {
        var category = mock(Category.class);
        when(category.getId()).thenReturn(1L);
        when(category.getName()).thenReturn("Оборудование");
        when(categories.findById(1L)).thenReturn(Optional.of(category));
        when(tickets.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(clock.instant()).thenReturn(NOW.plusNanos(789), NOW.plusSeconds(60));
        return category;
    }

    private void assertField(ApiException error, String field) {
        assertThat(error.getStatus().value()).isEqualTo(400);
        assertThat(error.getCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.getViolations())
                .singleElement()
                .satisfies(violation -> assertThat(violation.field()).isEqualTo(field));
    }
}
