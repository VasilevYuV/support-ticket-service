package ru.vasilev.support.ticket.validation;

import org.springframework.stereotype.Component;
import ru.vasilev.support.common.error.ApiException;
import ru.vasilev.support.ticket.dto.TicketSearchRequest;
import ru.vasilev.support.ticket.entity.TicketPriority;
import ru.vasilev.support.ticket.entity.TicketStatus;

@Component
public class TicketSearchParser {
    public TicketSearchRequest parse(
            String status,
            String priority,
            String categoryId,
            String overdue,
            String page,
            String size) {
        return new TicketSearchRequest(
                enumValue(status, "status", TicketStatus.class),
                enumValue(priority, "priority", TicketPriority.class),
                categoryId == null ? null : number(categoryId, "categoryId", 1, Long.MAX_VALUE),
                booleanValue(overdue),
                page == null ? 0 : (int) number(page, "page", 0, Integer.MAX_VALUE),
                size == null ? 20 : (int) number(size, "size", 1, 100));
    }

    private long number(String value, String field, long min, long max) {
        if (!value.matches("[0-9]+")) {
            throw ApiException.invalidRequest(field, "Ожидается целое число из десятичных цифр.");
        }
        try {
            long number = Long.parseLong(value);
            if (number < min || number > max) {
                throw ApiException.validation(
                        field, "Значение должно быть от " + min + " до " + max + ".");
            }
            return number;
        } catch (NumberFormatException exception) {
            throw ApiException.invalidRequest(field, "Число выходит за допустимый диапазон.");
        }
    }

    private <E extends Enum<E>> E enumValue(String value, String field, Class<E> type) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw ApiException.invalidRequest(field, "Неизвестное значение перечисления.");
        }
    }

    private Boolean booleanValue(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default ->
                    throw ApiException.invalidRequest("overdue", "Допустимы только true и false.");
        };
    }
}
