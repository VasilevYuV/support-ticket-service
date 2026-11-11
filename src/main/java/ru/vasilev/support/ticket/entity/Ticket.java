package ru.vasilev.support.ticket.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import ru.vasilev.support.category.entity.Category;

@Entity
@Table(name = "tickets")
public class Ticket {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 200, updatable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false, updatable = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private TicketPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 11)
    private TicketStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected Ticket() {}

    public Ticket(
            String title,
            String description,
            Category category,
            TicketPriority priority,
            Instant createdAt,
            Instant dueAt) {
        this.title = title;
        this.description = description;
        this.category = category;
        this.priority = priority;
        this.status = TicketStatus.NEW;
        this.createdAt = createdAt;
        this.dueAt = dueAt;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Category getCategory() {
        return category;
    }

    public TicketPriority getPriority() {
        return priority;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
