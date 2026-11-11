package ru.vasilev.support.common.json;

import java.util.Set;
import ru.vasilev.support.common.error.ApiException;
import tools.jackson.databind.JsonNode;

public final class StrictJsonObject {
    private final JsonNode object;

    public StrictJsonObject(JsonNode object, Set<String> allowedFields) {
        if (!object.isObject()) {
            throw ApiException.invalidRequest(null, "Тело запроса должно быть JSON-объектом.");
        }
        for (String field : object.propertyNames()) {
            if (!allowedFields.contains(field)) {
                throw ApiException.invalidRequest(field, "Неизвестное поле запроса.");
            }
        }
        this.object = object;
    }

    public String string(String field) {
        var value = object.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw ApiException.invalidRequest(field, "Ожидается строка.");
        }
        var text = value.stringValue();
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isHighSurrogate(character)
                    && index + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(index + 1))) {
                index++;
            } else if (character == 0 || Character.isSurrogate(character)) {
                throw ApiException.invalidRequest(
                        field, "Строка содержит недопустимый символ Unicode.");
            }
        }
        return text;
    }

    public Long longNumber(String field) {
        var value = object.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw ApiException.invalidRequest(field, "Ожидается целое число в пределах int64.");
        }
        return value.longValue();
    }

    public <E extends Enum<E>> E enumValue(String field, Class<E> type) {
        var value = string(field);
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw ApiException.invalidRequest(field, "Неизвестное значение перечисления.");
        }
    }
}
