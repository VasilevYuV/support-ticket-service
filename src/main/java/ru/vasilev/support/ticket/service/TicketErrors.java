package ru.vasilev.support.ticket.service;

import java.util.List;
import org.springframework.http.HttpStatus;
import ru.vasilev.support.common.error.ApiException;

final class TicketErrors {
    private TicketErrors() {}

    static ApiException notFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "Заявка не найдена.", List.of());
    }
}
