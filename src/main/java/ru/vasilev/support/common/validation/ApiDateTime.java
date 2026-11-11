package ru.vasilev.support.common.validation;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.regex.Pattern;
import ru.vasilev.support.common.error.ApiException;

public final class ApiDateTime {
    private static final Pattern FORMAT =
            Pattern.compile(
                    "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-5][0-9](?:\\.[0-9]{1,6})?(?:Z|[+-][0-9]{2}:[0-9]{2})");

    private ApiDateTime() {}

    public static Instant parse(String value, String field) {
        try {
            if (!FORMAT.matcher(value).matches()) {
                throw new DateTimeException("Неверный формат даты.");
            }
            var dateTime = OffsetDateTime.parse(value);
            var instant = dateTime.toInstant();
            int utcYear = instant.atOffset(ZoneOffset.UTC).getYear();
            if (dateTime.getYear() < 1 || utcYear < 1 || utcYear > 9999) {
                throw new DateTimeException("Год вне допустимого диапазона.");
            }
            return instant;
        } catch (DateTimeException exception) {
            throw ApiException.invalidRequest(
                    field,
                    "Нужна корректная дата со смещением, годом 0001-9999 и точностью не более шести дробных знаков; год в UTC должен быть в том же диапазоне.");
        }
    }
}
