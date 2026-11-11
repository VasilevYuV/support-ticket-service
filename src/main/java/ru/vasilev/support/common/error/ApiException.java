package ru.vasilev.support.common.error;

import java.util.List;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<FieldViolation> violations;

    public ApiException(
            HttpStatus status, String code, String message, List<FieldViolation> violations) {
        super(message);
        this.status = status;
        this.code = code;
        this.violations = List.copyOf(violations);
    }

    public static ApiException validation(String field, String message) {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Ошибка проверки данных запроса.",
                List.of(new FieldViolation(field, message)));
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static ApiException invalidRequest(String field, String message) {
        var violations =
                field == null
                        ? List.<FieldViolation>of()
                        : List.of(new FieldViolation(field, message));
        return new ApiException(
                HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Неверный формат запроса.", violations);
    }

    public String getCode() {
        return code;
    }

    public List<FieldViolation> getViolations() {
        return violations;
    }
}
