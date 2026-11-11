package ru.vasilev.support.ticket.controller;

import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.vasilev.support.common.validation.ApiUuid;
import ru.vasilev.support.ticket.dto.CreateTicketRequest;
import ru.vasilev.support.ticket.dto.ReplaceTicketDetailsRequest;
import ru.vasilev.support.ticket.dto.TicketResponse;
import ru.vasilev.support.ticket.service.TicketService;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {
    private final TicketService service;

    public TicketController(TicketService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TicketResponse> create(@RequestBody CreateTicketRequest request) {
        var ticket = service.create(request);
        return ResponseEntity.created(URI.create("/api/tickets/" + ticket.id())).body(ticket);
    }

    @GetMapping("/{id}")
    public TicketResponse getById(@PathVariable String id) {
        return service.getById(ApiUuid.parse(id, "id"));
    }

    @PutMapping(value = "/{id}/details", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TicketResponse replaceDetails(
            @PathVariable String id, @RequestBody ReplaceTicketDetailsRequest request) {
        return service.replaceDetails(ApiUuid.parse(id, "id"), request);
    }
}
