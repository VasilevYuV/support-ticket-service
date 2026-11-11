package ru.vasilev.support.ticket.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import ru.vasilev.support.category.entity.Category;
import ru.vasilev.support.common.error.ApiException;
import ru.vasilev.support.ticket.dto.ChangeTicketStatusRequest;
import ru.vasilev.support.ticket.entity.*;
import ru.vasilev.support.ticket.mapper.TicketMapper;
import ru.vasilev.support.ticket.repository.*;
import ru.vasilev.support.ticket.validation.TicketRequestValidator;

class TicketWorkflowServiceTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-11T09:00:00.123456Z");
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final TicketStatusHistoryRepository history = mock(TicketStatusHistoryRepository.class);
    private final Clock clock = mock(Clock.class);
    private TicketWorkflowService service;

    @BeforeEach
    void setUp() {
        service =
                new TicketWorkflowService(
                        tickets, history, new TicketRequestValidator(), new TicketMapper(), clock);
    }

    @ParameterizedTest
    @MethodSource("allowedTransitions")
    void savesOneHistoryEntryWithSameClosingTime(TicketStatus from, TicketStatus to) {
        var ticket = ticketIn(from);
        when(clock.instant()).thenReturn(NOW.plusNanos(789));
        var result = service.changeStatus(ID, new ChangeTicketStatusRequest(to));
        assertThat(result.status()).isEqualTo(to);
        assertThat(result.closedAt()).isEqualTo(to == TicketStatus.IN_PROGRESS ? null : NOW);
        assertThat(result.title()).isEqualTo("Заявка");
        assertThat(result.description()).isEqualTo("Описание");
        assertThat(result.createdAt()).isEqualTo(NOW.minusSeconds(60));
        var entry = ArgumentCaptor.forClass(TicketStatusHistory.class);
        var order = inOrder(tickets, clock, history);
        order.verify(tickets).findByIdForUpdate(ID);
        order.verify(clock).instant();
        order.verify(tickets).flush();
        order.verify(history).save(entry.capture());
        verify(clock, times(1)).instant();
        assertThat(entry.getValue().getTicket()).isSameAs(ticket);
        assertThat(entry.getValue().getFromStatus()).isEqualTo(from);
        assertThat(entry.getValue().getToStatus()).isEqualTo(to);
        assertThat(entry.getValue().getChangedAt()).isEqualTo(NOW);
    }

    static Stream<Arguments> allowedTransitions() {
        return Stream.of(
                Arguments.of(TicketStatus.NEW, TicketStatus.IN_PROGRESS),
                Arguments.of(TicketStatus.NEW, TicketStatus.CANCELLED),
                Arguments.of(TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED),
                Arguments.of(TicketStatus.IN_PROGRESS, TicketStatus.CANCELLED));
    }

    @ParameterizedTest
    @MethodSource("forbiddenTransitions")
    void rejectsForbiddenPairWithoutMutation(TicketStatus from, TicketStatus to) {
        var ticket = ticketIn(from);
        var closedAt = ticket.getClosedAt();
        assertThatThrownBy(() -> service.changeStatus(ID, new ChangeTicketStatusRequest(to)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.getStatus().value()).isEqualTo(409);
                            assertThat(error.getCode()).isEqualTo("STATE_CONFLICT");
                        });
        assertThat(ticket.getStatus()).isEqualTo(from);
        assertThat(ticket.getClosedAt()).isEqualTo(closedAt);
        verify(tickets, never()).flush();
        verifyNoInteractions(history, clock);
    }

    static Stream<Arguments> forbiddenTransitions() {
        return Stream.of(
                        "NEW,NEW",
                        "NEW,RESOLVED",
                        "IN_PROGRESS,NEW",
                        "IN_PROGRESS,IN_PROGRESS",
                        "RESOLVED,NEW",
                        "RESOLVED,IN_PROGRESS",
                        "RESOLVED,RESOLVED",
                        "RESOLVED,CANCELLED",
                        "CANCELLED,NEW",
                        "CANCELLED,IN_PROGRESS",
                        "CANCELLED,RESOLVED",
                        "CANCELLED,CANCELLED")
                .map(pair -> pair.split(","))
                .map(pair ->
                        Arguments.of(TicketStatus.valueOf(pair[0]), TicketStatus.valueOf(pair[1])));
    }

    @Test
    void validatesStatusBeforeLookup() {
        assertThatThrownBy(() -> service.changeStatus(ID, new ChangeTicketStatusRequest(null)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(400));
        verifyNoInteractions(tickets, history, clock);
    }

    @Test
    void reportsMissingTicketBeforeReadingClock() {
        when(tickets.findByIdForUpdate(ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() ->
                        service.changeStatus(
                                ID, new ChangeTicketStatusRequest(TicketStatus.CANCELLED)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(404));
        verifyNoInteractions(history, clock);
    }

    @Test
    void propagatesHistoryFailureToTransactionBoundary() {
        ticketIn(TicketStatus.NEW);
        when(clock.instant()).thenReturn(NOW);
        var failure = new IllegalStateException("Сбой записи истории.");
        when(history.save(any())).thenThrow(failure);
        assertThatThrownBy(() ->
                        service.changeStatus(
                                ID, new ChangeTicketStatusRequest(TicketStatus.CANCELLED)))
                .isSameAs(failure);
    }

    @Test
    void doesNotWriteHistoryWhenTicketFlushFails() {
        ticketIn(TicketStatus.NEW);
        when(clock.instant()).thenReturn(NOW);
        var failure = new IllegalStateException("Сбой записи заявки.");
        doThrow(failure).when(tickets).flush();
        assertThatThrownBy(() ->
                        service.changeStatus(
                                ID, new ChangeTicketStatusRequest(TicketStatus.CANCELLED)))
                .isSameAs(failure);
        verifyNoInteractions(history);
    }

    @Test
    void distinguishesMissingTicketFromEmptyHistory() {
        when(tickets.existsById(ID)).thenReturn(false);
        assertThatThrownBy(() -> service.getHistory(ID)).isInstanceOf(ApiException.class);
        verifyNoInteractions(history);
        when(tickets.existsById(ID)).thenReturn(true);
        when(history.findByTicketIdOrderByIdAsc(ID)).thenReturn(List.of());
        assertThat(service.getHistory(ID)).isEmpty();
    }

    private Ticket ticketIn(TicketStatus status) {
        var category = mock(Category.class);
        when(category.getId()).thenReturn(1L);
        when(category.getName()).thenReturn("Оборудование");
        var ticket =
                new Ticket(
                        "Заявка",
                        "Описание",
                        category,
                        TicketPriority.HIGH,
                        NOW.minusSeconds(60),
                        null);
        ticket.changeStatus(status, NOW.minusSeconds(30));
        when(tickets.findByIdForUpdate(ID)).thenReturn(Optional.of(ticket));
        return ticket;
    }
}
