package ru.vasilev.support.common.error;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private final Clock clock;

    public ApiExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(
            ApiException exception, HttpServletRequest request) {
        return response(
                exception.getStatus(),
                exception.getCode(),
                exception.getMessage(),
                exception.getViolations(),
                request);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiError> handleInvalidRequest(
            Exception exception, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "Неверный формат запроса.",
                List.of(),
                request);
    }

    private ResponseEntity<ApiError> response(
            HttpStatus status,
            String code,
            String message,
            List<FieldViolation> violations,
            HttpServletRequest request) {
        var error =
                new ApiError(
                        clock.instant().truncatedTo(ChronoUnit.MICROS),
                        status.value(),
                        code,
                        message,
                        request.getRequestURI(),
                        violations);
        return ResponseEntity.status(status).body(error);
    }
}
