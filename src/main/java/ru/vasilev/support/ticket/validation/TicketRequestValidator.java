package ru.vasilev.support.ticket.validation;

import org.springframework.stereotype.Component;
import ru.vasilev.support.common.error.ApiException;
import ru.vasilev.support.common.validation.TextValues;
import ru.vasilev.support.ticket.dto.CreateTicketRequest;
import ru.vasilev.support.ticket.dto.ReplaceTicketDetailsRequest;

@Component
public class TicketRequestValidator {
    public void validate(CreateTicketRequest request) {
        if (request.title() == null || TextValues.stripWhitespace(request.title()).isEmpty()) {
            throw ApiException.validation("title", "Заголовок обязателен.");
        }
        if (length(request.title()) > 200) {
            throw ApiException.validation("title", "Заголовок не должен превышать 200 символов.");
        }
        if (request.categoryId() == null || request.categoryId() <= 0) {
            throw ApiException.validation(
                    "categoryId", "Нужен положительный идентификатор категории.");
        }
        validateDescription(request.description());
    }

    public void validate(ReplaceTicketDetailsRequest request) {
        if (request.description() == null) {
            throw ApiException.validation("description", "Описание не должно быть null.");
        }
        if (request.priority() == null) {
            throw ApiException.validation("priority", "Приоритет обязателен.");
        }
        validateDescription(request.description());
    }

    private void validateDescription(String description) {
        if (description != null && length(description) > 10000) {
            throw ApiException.validation(
                    "description", "Описание не должно превышать 10000 символов.");
        }
    }

    private int length(String value) {
        return value.codePointCount(0, value.length());
    }
}
