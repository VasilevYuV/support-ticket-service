package ru.vasilev.support.ticket.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.vasilev.support.ticket.dto.TicketPageResponse;
import ru.vasilev.support.ticket.service.TicketSearchService;
import ru.vasilev.support.ticket.validation.TicketSearchParser;

@RestController
public class TicketSearchController {
    private final TicketSearchService service;
    private final TicketSearchParser parser;

    public TicketSearchController(TicketSearchService service, TicketSearchParser parser) {
        this.service = service;
        this.parser = parser;
    }

    @GetMapping("/api/tickets")
    public TicketPageResponse search(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "priority", required = false) String priority,
            @RequestParam(name = "categoryId", required = false) String categoryId,
            @RequestParam(name = "overdue", required = false) String overdue,
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size) {
        return service.search(parser.parse(status, priority, categoryId, overdue, page, size));
    }
}
