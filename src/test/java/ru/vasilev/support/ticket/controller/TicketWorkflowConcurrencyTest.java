package ru.vasilev.support.ticket.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TicketWorkflowConcurrencyTest extends TicketWorkflowTestSupport {
    private static final long GATE = 918273L;

    @ParameterizedTest
    @ValueSource(strings = {"RESOLVED", "CANCELLED"})
    void commitsExactlyOneCompetingTerminalTransition(String firstStatus) throws Exception {
        var id = createTicket("IN_PROGRESS");
        var previous = history(id);
        var secondStatus = firstStatus.equals("RESOLVED") ? "CANCELLED" : "RESOLVED";
        var results = runInLockOrder(() -> change(id, firstStatus), () -> change(id, secondStatus));
        assertThat(results.first().status()).isEqualTo(200);
        assertError(results.second(), 409, "STATE_CONFLICT", statusPath(id));
        var saved = snapshot(id);
        var entries = history(id);
        assertThat(saved).containsEntry("status", firstStatus);
        assertThat(entries).hasSize(previous.size() + 1);
        assertThat(entries.subList(0, previous.size())).isEqualTo(previous);
        assertThat(entries.getLast())
                .containsEntry("from_status", "IN_PROGRESS")
                .containsEntry("to_status", firstStatus);
        assertThat(saved.get("closed_at")).isEqualTo(entries.getLast().get("changed_at"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void serializesClosingAndEditingInBothOrders(boolean closingFirst) throws Exception {
        var id = createTicket("IN_PROGRESS");
        var previous = history(id);
        Callable<ApiResult> close = () -> change(id, "RESOLVED");
        Callable<ApiResult> edit = () -> edit(id);
        var results = closingFirst ? runInLockOrder(close, edit) : runInLockOrder(edit, close);
        assertThat(results.first().status()).isEqualTo(200);
        if (closingFirst) {
            assertError(results.second(), 409, "STATE_CONFLICT", "/api/tickets/" + id + "/details");
        } else {
            assertThat(results.second().status()).isEqualTo(200);
        }
        var saved = snapshot(id);
        assertThat(saved)
                .containsEntry("status", "RESOLVED")
                .containsEntry("description", closingFirst ? "Исходное" : "Изменено")
                .containsEntry("priority", closingFirst ? "HIGH" : "LOW");
        if (!closingFirst) {
            assertThat(saved.get("due_at")).isNull();
        }
        var entries = history(id);
        assertThat(entries).hasSize(previous.size() + 1);
        assertThat(entries.subList(0, previous.size())).isEqualTo(previous);
        assertThat(entries.getLast())
                .containsEntry("from_status", "IN_PROGRESS")
                .containsEntry("to_status", "RESOLVED");
        assertThat(saved.get("closed_at")).isEqualTo(entries.getLast().get("changed_at"));
    }

    private ApiResult edit(UUID id) throws Exception {
        return send(
                "PUT",
                "/api/tickets/" + id + "/details",
                """
                {"description":"Изменено","priority":"LOW","dueAt":null}
                """);
    }

    private Results runInLockOrder(Callable<ApiResult> first, Callable<ApiResult> second)
            throws Exception {
        // Триггер задерживает первый UPDATE после получения блокировки строки.
        jdbc.execute(
                """
                CREATE FUNCTION test_pause_ticket_update() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    PERFORM pg_advisory_xact_lock(918273);
                    RETURN NEW;
                END;
                $$
                """);
        try {
            jdbc.execute(
                    "CREATE TRIGGER test_pause_ticket_update BEFORE UPDATE ON tickets FOR EACH ROW EXECUTE FUNCTION test_pause_ticket_update()");
            try (var gate = jdbc.getDataSource().getConnection();
                    var executor = Executors.newFixedThreadPool(2)) {
                execute(gate, "SELECT pg_advisory_lock(" + GATE + ")");
                int gatePid;
                try (var statement = gate.createStatement();
                        var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
                    rows.next();
                    gatePid = rows.getInt(1);
                }
                try {
                    var firstFuture = executor.submit(first);
                    awaitBlockedBy(gatePid);
                    int firstPid =
                            jdbc.queryForObject(
                                    "SELECT pid FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                                    Integer.class,
                                    gatePid);
                    var secondFuture = executor.submit(second);
                    awaitBlockedBy(firstPid);
                    execute(gate, "SELECT pg_advisory_unlock(" + GATE + ")");
                    return new Results(
                            firstFuture.get(10, TimeUnit.SECONDS),
                            secondFuture.get(10, TimeUnit.SECONDS));
                } finally {
                    execute(gate, "SELECT pg_advisory_unlock_all()");
                }
            }
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS test_pause_ticket_update ON tickets");
            jdbc.execute("DROP FUNCTION test_pause_ticket_update()");
        }
    }

    private void awaitBlockedBy(int pid) {
        await().atMost(Duration.ofSeconds(5))
                .until(() ->
                        jdbc.queryForObject(
                                "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))",
                                Boolean.class,
                                pid));
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record Results(ApiResult first, ApiResult second) {}
}
