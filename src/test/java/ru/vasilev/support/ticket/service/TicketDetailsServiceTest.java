package ru.vasilev.support.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.vasilev.support.category.entity.Category;
import ru.vasilev.support.category.service.CategoryService;
import ru.vasilev.support.common.error.ApiException;
import ru.vasilev.support.ticket.dto.ReplaceTicketDetailsRequest;
import ru.vasilev.support.ticket.entity.Ticket;
import ru.vasilev.support.ticket.entity.TicketPriority;
import ru.vasilev.support.ticket.entity.TicketStatus;
import ru.vasilev.support.ticket.mapper.TicketMapper;
import ru.vasilev.support.ticket.repository.TicketRepository;
import ru.vasilev.support.ticket.validation.TicketRequestValidator;

class TicketDetailsServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-11T09:00:00Z");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final TicketRepository repository = mock(TicketRepository.class);
    private TicketService service;

    @BeforeEach
    void setUp() {
        service =
                new TicketService(
                        repository,
                        mock(CategoryService.class),
                        new TicketRequestValidator(),
                        new TicketMapper(),
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @EnumSource(
            value = TicketStatus.class,
            names = {"NEW", "IN_PROGRESS"})
    void replacesOnlyEditableFieldsInOpenState(TicketStatus status) {
        var ticket = ticketIn(status);
        var category = ticket.getCategory();
        var dueAt = NOW.minusSeconds(1);
        var result =
                service.replaceDetails(
                        ID,
                        new ReplaceTicketDetailsRequest(
                                "Новое описание", TicketPriority.HIGH, dueAt));
        assertThat(result.description()).isEqualTo("Новое описание");
        assertThat(result.priority()).isEqualTo(TicketPriority.HIGH);
        assertThat(result.dueAt()).isEqualTo(dueAt);
        assertThat(result.overdue()).isTrue();
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.title()).isEqualTo("Заявка");
        assertThat(result.createdAt()).isEqualTo(NOW);
        assertThat(result.closedAt()).isNull();
        assertThat(ticket.getCategory()).isSameAs(category);
    }

    @ParameterizedTest
    @EnumSource(
            value = TicketStatus.class,
            names = {"RESOLVED", "CANCELLED"})
    void rejectsTerminalStateWithoutChangingDetails(TicketStatus status) {
        var ticket = ticketIn(status);
        assertThatThrownBy(() ->
                        service.replaceDetails(
                                ID,
                                new ReplaceTicketDetailsRequest(
                                        "Новое", TicketPriority.HIGH, null)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.getStatus().value()).isEqualTo(409);
                            assertThat(error.getCode()).isEqualTo("STATE_CONFLICT");
                        });
        assertThat(ticket.getDescription()).isEqualTo("Исходное");
        assertThat(ticket.getPriority()).isEqualTo(TicketPriority.LOW);
        assertThat(ticket.getDueAt()).isEqualTo(NOW.plusSeconds(10));
        verify(ticket, never()).replaceDetails(anyString(), any(), any());
    }

    @Test
    void clearsDescriptionAndDueDate() {
        var ticket = ticketIn(TicketStatus.NEW);
        var result =
                service.replaceDetails(
                        ID, new ReplaceTicketDetailsRequest("", TicketPriority.MEDIUM, null));
        assertThat(ticket.getDescription()).isEmpty();
        assertThat(ticket.getDueAt()).isNull();
        assertThat(result.overdue()).isFalse();
    }

    @Test
    void validatesBeforeLookingUpTicket() {
        assertThatThrownBy(() ->
                        service.replaceDetails(
                                ID,
                                new ReplaceTicketDetailsRequest(null, TicketPriority.HIGH, null)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(400));
        verifyNoInteractions(repository);
    }

    @Test
    void reportsMissingTicket() {
        when(repository.findByIdForUpdate(ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() ->
                        service.replaceDetails(
                                ID, new ReplaceTicketDetailsRequest("", TicketPriority.HIGH, null)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.getStatus().value()).isEqualTo(404);
                            assertThat(error.getCode()).isEqualTo("TICKET_NOT_FOUND");
                        });
    }

    private Ticket ticketIn(TicketStatus status) {
        var category = mock(Category.class);
        when(category.getId()).thenReturn(1L);
        when(category.getName()).thenReturn("Оборудование");
        var ticket =
                spy(
                        new Ticket(
                                "Заявка",
                                "Исходное",
                                category,
                                TicketPriority.LOW,
                                NOW,
                                NOW.plusSeconds(10)));
        when(ticket.getStatus()).thenReturn(status);
        when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(ticket));
        return ticket;
    }
}
