package ru.vasilev.support.ticket.controller;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.vasilev.support.ticket.dto.CreateTicketRequest;
import ru.vasilev.support.ticket.dto.TicketResponse;
import ru.vasilev.support.ticket.service.TicketService;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {
    private final TicketService service;

    public TicketController(TicketService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TicketResponse> create(@RequestBody CreateTicketRequest request) {
        var ticket = service.create(request);
        return ResponseEntity.created(URI.create("/api/tickets/" + ticket.id())).body(ticket);
    }

    @GetMapping("/{id}")
    public TicketResponse getById(@PathVariable UUID id) {
        return service.getById(id);
    }
}
