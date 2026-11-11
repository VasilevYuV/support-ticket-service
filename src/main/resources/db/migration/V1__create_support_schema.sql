CREATE TABLE categories (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name varchar(100) NOT NULL,
    CONSTRAINT uq_categories_name UNIQUE (name),
    CONSTRAINT ck_categories_name_not_blank CHECK (char_length(btrim(name)) > 0)
);

CREATE TABLE tickets (
    id uuid PRIMARY KEY,
    title varchar(200) NOT NULL,
    description text NOT NULL DEFAULT '',
    category_id bigint NOT NULL,
    priority varchar(6) NOT NULL DEFAULT 'MEDIUM',
    status varchar(11) NOT NULL DEFAULT 'NEW',
    created_at timestamptz(6) NOT NULL,
    due_at timestamptz(6),
    closed_at timestamptz(6),
    CONSTRAINT fk_tickets_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT ck_tickets_title_not_blank CHECK (char_length(btrim(title)) > 0),
    CONSTRAINT ck_tickets_description_length CHECK (char_length(description) <= 10000),
    CONSTRAINT ck_tickets_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_tickets_status CHECK (status IN ('NEW', 'IN_PROGRESS', 'RESOLVED', 'CANCELLED')),
    CONSTRAINT ck_tickets_closed_at CHECK (
        (status IN ('NEW', 'IN_PROGRESS') AND closed_at IS NULL)
        OR (status IN ('RESOLVED', 'CANCELLED') AND closed_at IS NOT NULL AND closed_at >= created_at)
    )
);

CREATE TABLE ticket_status_history (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id uuid NOT NULL,
    from_status varchar(11) NOT NULL,
    to_status varchar(11) NOT NULL,
    changed_at timestamptz(6) NOT NULL,
    CONSTRAINT fk_history_ticket FOREIGN KEY (ticket_id) REFERENCES tickets (id),
    CONSTRAINT ck_history_transition CHECK (
        (from_status = 'NEW' AND to_status IN ('IN_PROGRESS', 'CANCELLED'))
        OR (from_status = 'IN_PROGRESS' AND to_status IN ('RESOLVED', 'CANCELLED'))
    )
);

CREATE INDEX idx_tickets_created_at_id ON tickets (created_at DESC, id DESC);
CREATE INDEX idx_tickets_category ON tickets (category_id);
CREATE INDEX idx_tickets_open_due_at ON tickets (due_at)
    WHERE status IN ('NEW', 'IN_PROGRESS') AND due_at IS NOT NULL;
CREATE INDEX idx_history_ticket_id ON ticket_status_history (ticket_id, id);
