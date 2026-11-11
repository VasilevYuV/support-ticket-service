package ru.vasilev.support.ticket.dto;

import ru.vasilev.support.ticket.entity.TicketStatus;
import ru.vasilev.support.ticket.validation.ChangeTicketStatusRequestDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = ChangeTicketStatusRequestDeserializer.class)
public record ChangeTicketStatusRequest(TicketStatus status) {}
