package ru.vasilev.support.report.repository;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import ru.vasilev.support.report.dto.ReportTicket;

@NullMarked
@FunctionalInterface
public interface ReportDataSource {
    /** Возвращает одну выборку текущих состояний заявок с from <= createdAt < to. */
    List<ReportTicket> findCreatedBetween(Instant from, Instant to);
}
