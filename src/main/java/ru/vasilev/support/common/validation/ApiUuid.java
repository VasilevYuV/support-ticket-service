package ru.vasilev.support.common.validation;

import java.util.UUID;
import java.util.regex.Pattern;
import ru.vasilev.support.common.error.ApiException;

public final class ApiUuid {
    private static final Pattern FORMAT =
            Pattern.compile(
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private ApiUuid() {}

    public static UUID parse(String value, String field) {
        if (!FORMAT.matcher(value).matches()) {
            throw ApiException.invalidRequest(field, "UUID должен иметь формат 8-4-4-4-12.");
        }
        return UUID.fromString(value);
    }
}
