package ru.vasilev.support.ticket.validation;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import ru.vasilev.support.common.error.ApiException;
import ru.vasilev.support.common.error.FieldViolation;
import ru.vasilev.support.ticket.dto.CreateTicketRequest;

@Component
public class CreateTicketValidator {
    public void validate(CreateTicketRequest request) {
        if (request.title() == null || request.title().isBlank()) {
            throw ApiException.validation("title", "Заголовок обязателен.");
        }
        if (length(request.title()) > 200) {
            throw ApiException.validation("title", "Заголовок не должен превышать 200 символов.");
        }
        if (request.categoryId() == null || request.categoryId() <= 0) {
            throw ApiException.validation(
                    "categoryId", "Нужен положительный идентификатор категории.");
        }
        if (request.description() != null && length(request.description()) > 10000) {
            throw ApiException.validation(
                    "description", "Описание не должно превышать 10000 символов.");
        }
        if (request.dueAt() != null && request.dueAt().getNano() % 1000 != 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "Неверный формат срока.",
                    List.of(
                            new FieldViolation(
                                    "dueAt", "Допустимо не более шести дробных знаков секунды.")));
        }
    }

    private int length(String value) {
        return value.codePointCount(0, value.length());
    }
}
