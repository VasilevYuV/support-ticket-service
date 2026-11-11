package ru.vasilev.support.ticket.controller;

import java.net.URI;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.vasilev.support.common.validation.ApiUuid;
import ru.vasilev.support.ticket.dto.ChangeTicketStatusRequest;
import ru.vasilev.support.ticket.dto.CreateTicketRequest;
import ru.vasilev.support.ticket.dto.ReplaceTicketDetailsRequest;
import ru.vasilev.support.ticket.dto.StatusHistoryResponse;
import ru.vasilev.support.ticket.dto.TicketResponse;
import ru.vasilev.support.ticket.service.TicketService;
import ru.vasilev.support.ticket.service.TicketWorkflowService;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {
    private final TicketService service;
    private final TicketWorkflowService workflow;

    public TicketController(TicketService service, TicketWorkflowService workflow) {
        this.service = service;
        this.workflow = workflow;
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

    @PatchMapping(value = "/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TicketResponse changeStatus(
            @PathVariable String id, @RequestBody ChangeTicketStatusRequest request) {
        return workflow.changeStatus(ApiUuid.parse(id, "id"), request);
    }

    @GetMapping("/{id}/history")
    public List<StatusHistoryResponse> getHistory(@PathVariable String id) {
        return workflow.getHistory(ApiUuid.parse(id, "id"));
    }
}
