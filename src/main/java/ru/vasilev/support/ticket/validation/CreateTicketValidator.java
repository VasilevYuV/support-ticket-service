package ru.vasilev.support.ticket.validation;

import org.springframework.stereotype.Component;
import ru.vasilev.support.common.error.ApiException;
import ru.vasilev.support.common.validation.TextValues;
import ru.vasilev.support.ticket.dto.CreateTicketRequest;

@Component
public class CreateTicketValidator {
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
        if (request.description() != null && length(request.description()) > 10000) {
            throw ApiException.validation(
                    "description", "Описание не должно превышать 10000 символов.");
        }
    }

    private int length(String value) {
        return value.codePointCount(0, value.length());
    }
}
