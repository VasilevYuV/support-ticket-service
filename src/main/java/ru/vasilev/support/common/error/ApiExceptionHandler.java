package ru.vasilev.support.common.error;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final Clock clock;

    public ApiExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApiException(
            ApiException exception, HttpServletRequest request) {
        return response(
                exception.getStatus(),
                exception.getCode(),
                exception.getMessage(),
                exception.getViolations(),
                request,
                new HttpHeaders());
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ApiException apiException) {
                return handleApiException(apiException, servletRequest(request));
            }
        }
        return handleExceptionInternal(exception, null, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        var violations =
                exception instanceof MethodArgumentTypeMismatchException argument
                        ? List.of(
                                new FieldViolation(
                                        argument.getName(), "Неверный формат параметра."))
                        : List.<FieldViolation>of();
        return response(
                status,
                "INVALID_REQUEST",
                "Неверный формат запроса.",
                violations,
                servletRequest(request),
                headers);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String code =
                switch (status.value()) {
                    case 400 -> "INVALID_REQUEST";
                    case 404 -> "NOT_FOUND";
                    case 405 -> "METHOD_NOT_ALLOWED";
                    case 406 -> "NOT_ACCEPTABLE";
                    case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                    default -> "INTERNAL_ERROR";
                };
        String message =
                switch (status.value()) {
                    case 400 -> "Неверный формат запроса.";
                    case 404 -> "Ресурс не найден.";
                    case 405 -> "Метод не поддерживается для этого адреса.";
                    case 406 -> "Запрошенный формат ответа не поддерживается.";
                    case 415 -> "Тело запроса должно иметь тип application/json.";
                    default -> "Внутренняя ошибка сервера.";
                };
        return response(status, code, message, List.of(), servletRequest(request), headers);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(
            Exception exception, HttpServletRequest request) {
        LOG.error(
                "Ошибка обработки {} {}", request.getMethod(), request.getRequestURI(), exception);
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Внутренняя ошибка сервера.",
                List.of(),
                request,
                new HttpHeaders());
    }

    private HttpServletRequest servletRequest(WebRequest request) {
        return ((ServletWebRequest) request).getRequest();
    }

    private ResponseEntity<Object> response(
            HttpStatusCode status,
            String code,
            String message,
            List<FieldViolation> violations,
            HttpServletRequest request,
            HttpHeaders headers) {
        var error =
                new ApiError(
                        clock.instant().truncatedTo(ChronoUnit.MICROS),
                        status.value(),
                        code,
                        message,
                        request.getRequestURI(),
                        violations);
        var responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(error, responseHeaders, status);
    }
}
