package ru.vasilev.support.common.validation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashSet;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import ru.vasilev.support.common.error.ApiException;

public class QueryParameterInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || request.getQueryString() == null) {
            return true;
        }
        var allowed = new HashSet<String>();
        for (var parameter : method.getMethodParameters()) {
            var annotation = parameter.getParameterAnnotation(RequestParam.class);
            if (annotation != null) {
                String name = annotation.name().isEmpty() ? annotation.value() : annotation.name();
                allowed.add(name.isEmpty() ? parameter.getParameterName() : name);
            }
        }
        for (var parameter : request.getParameterMap().entrySet()) {
            String name = parameter.getKey();
            if (!allowed.contains(name)) {
                throw ApiException.invalidRequest(name, "Неизвестный параметр запроса.");
            }
            if (parameter.getValue().length != 1) {
                throw ApiException.invalidRequest(name, "Повтор параметра запроса не допускается.");
            }
            if (parameter.getValue()[0].isEmpty()) {
                throw ApiException.invalidRequest(name, "Параметр запроса не должен быть пустым.");
            }
        }
        return true;
    }
}
