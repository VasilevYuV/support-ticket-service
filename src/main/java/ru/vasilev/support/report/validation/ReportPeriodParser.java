package ru.vasilev.support.report.validation;

import java.time.Instant;
import org.springframework.stereotype.Component;
import ru.vasilev.support.common.error.ApiException;
import ru.vasilev.support.common.validation.ApiDateTime;
import ru.vasilev.support.report.dto.ReportPeriod;

@Component
public class ReportPeriodParser {
    public ReportPeriod parse(String from, String to) {
        return new ReportPeriod(requiredDate(from, "from"), requiredDate(to, "to"));
    }

    private Instant requiredDate(String value, String field) {
        if (value == null) {
            throw ApiException.validation(field, "Граница периода обязательна.");
        }
        return ApiDateTime.parse(value, field);
    }
}
