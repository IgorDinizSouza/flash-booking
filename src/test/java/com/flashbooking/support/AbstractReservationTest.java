package com.flashbooking.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.fasterxml.jackson.databind.JsonNode;

/** Helpers compartilhados pelos testes de reserva/expiracao (dados novos por teste, via API e SQL direto). */
public abstract class AbstractReservationTest extends AbstractIntegrationTest {

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcClient jdbc;

    protected UUID newEvent(int capacity) {
        return jdbc.sql("INSERT INTO events (name, total_capacity, available) VALUES (:n, :c, :c) RETURNING id")
                .param("n", "Evt " + UUID.randomUUID())
                .param("c", capacity)
                .query(UUID.class).single();
    }

    protected ResponseEntity<JsonNode> reserve(UUID eventId, int quantity) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "k-" + UUID.randomUUID());
        return rest.exchange("/events/" + eventId + "/reservations", HttpMethod.POST,
                new HttpEntity<>("{\"quantity\":" + quantity + "}", headers), JsonNode.class);
    }

    protected UUID reserveOk(UUID eventId, int quantity) {
        var res = reserve(eventId, quantity);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(res.getBody().get("id").asText());
    }

    protected ResponseEntity<JsonNode> delete(UUID id) {
        return rest.exchange("/reservations/" + id, HttpMethod.DELETE, null, JsonNode.class);
    }

    protected ResponseEntity<JsonNode> get(UUID id) {
        return rest.getForEntity("/reservations/" + id, JsonNode.class);
    }

    /** Move created_at e expires_at para o passado (created_at <= expires_at). */
    protected void expireInPast(UUID id) {
        jdbc.sql("UPDATE reservations SET created_at = NOW() - interval '2 hours', "
                + "expires_at = NOW() - interval '1 hour' WHERE id = :id").param("id", id).update();
    }

    /** Cria N reservas (quantity 1) ja vencidas direto no banco, baixando o estoque; retorna os ids. */
    protected List<UUID> seedExpired(UUID eventId, int count) {
        List<UUID> ids = jdbc.sql("INSERT INTO reservations (id, event_id, quantity, status, expires_at, created_at) "
                + "SELECT gen_random_uuid(), :e, 1, 'PENDING', NOW() - interval '1 hour' + (g * interval '1 second'), "
                + "NOW() - interval '2 hours' FROM generate_series(1, :n) g RETURNING id")
                .param("e", eventId).param("n", count).query(UUID.class).list();
        jdbc.sql("UPDATE events SET available = available - :n WHERE id = :e")
                .param("n", count).param("e", eventId).update();
        return ids;
    }

    protected String physicalStatus(UUID id) {
        return jdbc.sql("SELECT status FROM reservations WHERE id = :id").param("id", id)
                .query(String.class).single();
    }

    protected int historyCount(UUID reservationId, String action) {
        return jdbc.sql("SELECT COUNT(*) FROM reservation_history WHERE reservation_id = :id AND action = :a")
                .param("id", reservationId).param("a", action).query(Integer.class).single();
    }

    protected int historyCountForEvent(UUID eventId, String action) {
        return jdbc.sql("SELECT COUNT(*) FROM reservation_history WHERE event_id = :id AND action = :a")
                .param("id", eventId).param("a", action).query(Integer.class).single();
    }

    protected int countByStatus(UUID eventId, String status) {
        return jdbc.sql("SELECT COUNT(*) FROM reservations WHERE event_id = :id AND status = :s")
                .param("id", eventId).param("s", status).query(Integer.class).single();
    }

    /** Executa as tarefas em paralelo com largada sincronizada e devolve os resultados na ordem. */
    protected static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (var f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }
}
