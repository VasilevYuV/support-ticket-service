package ru.vasilev.support.ticket.validation;

import java.util.Set;
import ru.vasilev.support.common.json.StrictJsonObject;
import ru.vasilev.support.ticket.dto.ChangeTicketStatusRequest;
import ru.vasilev.support.ticket.entity.TicketStatus;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

public class ChangeTicketStatusRequestDeserializer
        extends ValueDeserializer<ChangeTicketStatusRequest> {
    @Override
    public ChangeTicketStatusRequest deserialize(
            JsonParser parser, DeserializationContext context) {
        var fields = new StrictJsonObject(parser.readValueAsTree(), Set.of("status"));
        fields.require("status");
        return new ChangeTicketStatusRequest(fields.enumValue("status", TicketStatus.class));
    }
}
