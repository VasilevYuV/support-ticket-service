package ru.vasilev.support.report.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.vasilev.support.report.dto.SummaryReport;
import ru.vasilev.support.report.service.SummaryReportService;
import ru.vasilev.support.report.validation.ReportPeriodParser;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final SummaryReportService service;
    private final ReportPeriodParser parser;

    public ReportController(SummaryReportService service, ReportPeriodParser parser) {
        this.service = service;
        this.parser = parser;
    }

    @GetMapping("/summary")
    public SummaryReport summary(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to) {
        var period = parser.parse(from, to);
        return service.generate(period.from(), period.to());
    }
}
