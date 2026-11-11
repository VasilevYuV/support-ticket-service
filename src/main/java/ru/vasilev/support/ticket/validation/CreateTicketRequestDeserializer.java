package ru.vasilev.support.ticket.validation;

import java.util.Set;
import ru.vasilev.support.common.json.StrictJsonObject;
import ru.vasilev.support.common.validation.ApiDateTime;
import ru.vasilev.support.ticket.dto.CreateTicketRequest;
import ru.vasilev.support.ticket.entity.TicketPriority;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

public class CreateTicketRequestDeserializer extends ValueDeserializer<CreateTicketRequest> {
    private static final Set<String> FIELDS =
            Set.of("title", "categoryId", "description", "priority", "dueAt");

    @Override
    public CreateTicketRequest deserialize(JsonParser parser, DeserializationContext context) {
        var fields = new StrictJsonObject(parser.readValueAsTree(), FIELDS);
        var title = fields.string("title");
        var categoryId = fields.longNumber("categoryId");
        var description = fields.string("description");
        var priority = fields.enumValue("priority", TicketPriority.class);
        var dueAt = fields.string("dueAt");
        return new CreateTicketRequest(
                title,
                categoryId,
                description,
                priority,
                dueAt == null ? null : ApiDateTime.parse(dueAt, "dueAt"));
    }
}
