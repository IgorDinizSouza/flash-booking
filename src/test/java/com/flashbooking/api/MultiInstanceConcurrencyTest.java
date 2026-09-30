package com.flashbooking.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashbooking.FlashBookingApplication;
import com.flashbooking.support.AbstractIntegrationTest;
import com.flashbooking.support.StockInvariant;

/**
 * Duas aplicacoes Spring completas na mesma JVM (portas aleatorias, INSTANCE_ID api1/api2) contra o MESMO
 * PostgreSQL, em um banco novo: as duas sobem em paralelo e disputam a migracao do Flyway. Prova que o
 * zero-oversell, a idempotencia e a expiracao nao dependem de nenhum estado em memoria de uma instancia.
 */
class MultiInstanceConcurrencyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private static String dbName;
    private static String jdbcUrl;
    private static JdbcClient jdbc;
    private static Pair pair;

    record Pair(ConfigurableApplicationContext ctx1, ConfigurableApplicationContext ctx2) implements AutoCloseable {

        String baseUrl(int i) {
            var ctx = i == 0 ? ctx1 : ctx2;
            return "http://localhost:" + ctx.getEnvironment().getProperty("local.server.port");
        }

        @Override
        public void close() {
            try {
                ctx1.close();
            } finally {
                ctx2.close();
            }
        }
    }

    record Resp(int status, JsonNode body, String instance, String replayed) {
    }

    private static ConfigurableApplicationContext start(String instanceId, boolean job) {
        return new SpringApplicationBuilder(FlashBookingApplication.class)
                .profiles("test")
                .run(
                        "--server.port=0",
                        "--spring.main.banner-mode=off",
                        "--INSTANCE_ID=" + instanceId,
                        "--spring.datasource.url=" + jdbcUrl,
                        "--spring.datasource.username=" + AbstractIntegrationTest.POSTGRES.getUsername(),
                        "--spring.datasource.password=" + AbstractIntegrationTest.POSTGRES.getPassword(),
                        "--booking.reservation.expiration-job-enabled=" + job,
                        "--booking.reservation.expiration-job-delay=100ms",
                        "--spring.datasource.hikari.minimum-idle=2",
                        "--booking.reservation.expiration-batch-size=5"
                );
    }

    /** Sobe as duas instancias em paralelo (exercita o lock de migracao do Flyway em banco vazio). */
    private static Pair startPair(boolean job) throws Exception {
        CompletableFuture<ConfigurableApplicationContext> f1 = CompletableFuture.supplyAsync(() -> start("api1", job));
        CompletableFuture<ConfigurableApplicationContext> f2 = CompletableFuture.supplyAsync(() -> start("api2", job));
        ConfigurableApplicationContext c1 = null;
        ConfigurableApplicationContext c2 = null;
        try {
            c1 = f1.get();
            c2 = f2.get();
        } catch (Exception e) {
            if (c1 != null) {
                c1.close();
            }
            f1.thenAccept(ConfigurableApplicationContext::close);
            f2.thenAccept(ConfigurableApplicationContext::close);
            throw e;
        }
        return new Pair(c1, c2);
    }

    @BeforeAll
    static void startInstances() throws Exception {
        var pg = AbstractIntegrationTest.POSTGRES;
        String base = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/";
        dbName = "multi_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + dbName);
        }
        jdbcUrl = base + dbName;
        jdbc = JdbcClient.create(new DriverManagerDataSource(jdbcUrl, pg.getUsername(), pg.getPassword()));
        pair = startPair(false);
    }

    @AfterAll
    static void stopInstances() {
        if (pair != null) {
            pair.close();
        }
    }

    // ---------- helpers ----------

    private static Resp send(HttpRequest.Builder req) throws Exception {
        HttpResponse<String> r = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode body = r.body() == null || r.body().isBlank() ? null : JSON.readTree(r.body());
        return new Resp(r.statusCode(), body, r.headers().firstValue("X-Instance-Id").orElse(null),
                r.headers().firstValue("Idempotent-Replayed").orElse(null));
    }

    private static Resp reserve(String baseUrl, UUID eventId, String key, int quantity) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/events/" + eventId + "/reservations"))
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString("{\"quantity\":" + quantity + "}")));
    }

    private static Resp cancel(String baseUrl, UUID id) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/reservations/" + id)).DELETE());
    }

    private static UUID newEvent(int capacity) {
        return jdbc.sql("INSERT INTO events (name, total_capacity, available) VALUES (:n, :c, :c) RETURNING id")
                .param("n", "Multi " + UUID.randomUUID()).param("c", capacity).query(UUID.class).single();
    }

    private static int count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Integer.class).single();
    }

    private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
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

    // ---------- testes ----------

    @Test
    void bothInstancesMigratedTheSharedDatabaseExactlyOnce() {
        assertThat(count("SELECT COUNT(*) FROM flyway_schema_history WHERE NOT success")).isZero();
        assertThat(count("SELECT COUNT(*) FROM flyway_schema_history"))
                .isEqualTo(count("SELECT COUNT(DISTINCT version) FROM flyway_schema_history"));
    }

    @Test
    void i3_twoHundredRequestsSplitAcrossTwoInstancesNeverOversell() throws Exception {
        UUID eventId = newEvent(50);
        List<Callable<Resp>> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String url = pair.baseUrl(i % 2);
            String key = "mi-" + UUID.randomUUID();
            tasks.add(() -> reserve(url, eventId, key, 1));
        }

        List<Resp> results = runConcurrently(tasks);

        assertThat(results.stream().filter(r -> r.status() != 201 && r.status() != 409).map(Resp::status).toList())
                .as("unexpected statuses (e.g. 503)").isEmpty();
        assertThat(results.stream().filter(r -> r.status() == 201).count()).isEqualTo(50);
        assertThat(results.stream().filter(r -> r.status() == 409).count()).isEqualTo(150);
        assertThat(results.stream().map(Resp::instance).collect(Collectors.toSet())).containsExactlyInAnyOrder("api1",
                "api2");
        assertThat(StockInvariant.available(jdbc, eventId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM reservations WHERE event_id = ? AND status = 'PENDING'", eventId))
                .isEqualTo(50);
        assertThat(count("SELECT COUNT(*) FROM reservations WHERE event_id = ?", eventId)).isEqualTo(50);
        assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE event_id = ? AND action = 'CREATED'",
                eventId)).isEqualTo(50);
        assertThat(count("SELECT COUNT(DISTINCT instance_id) FROM reservation_history WHERE event_id = ?", eventId))
                .as("both instances created reservations").isEqualTo(2);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void idempotencyHoldsAcrossInstances() throws Exception {
        UUID eventId = newEvent(10);
        String key = "mi-idem-" + UUID.randomUUID();
        List<Callable<Resp>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String url = pair.baseUrl(i % 2);
            tasks.add(() -> reserve(url, eventId, key, 2));
        }

        List<Resp> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
        assertThat(results.stream().map(r -> r.body().get("id").asText()).distinct()).hasSize(1);
        assertThat(results.stream().map(Resp::body).distinct()).hasSize(1);
        assertThat(results.stream().filter(r -> "true".equals(r.replayed())).count()).isEqualTo(19);
        assertThat(results.stream().map(Resp::instance).collect(Collectors.toSet())).containsExactlyInAnyOrder("api1",
                "api2");
        assertThat(count("SELECT COUNT(*) FROM reservations WHERE event_id = ?", eventId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE event_id = ?", eventId)).isEqualTo(1);
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(8);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void expirationJobsOfBothInstancesExpireEachReservationOnceWhileCancellationsRun() throws Exception {
        try (Pair jobs = startPair(true)) {
            // 200 vencidas semeadas por SQL (uma unica instrucao: aparecem atomicamente para os jobs)
            UUID expiredEvent = newEvent(200);
            List<UUID> expiredIds = jdbc.sql("INSERT INTO reservations (id, event_id, quantity, status, expires_at, "
                    + "created_at) SELECT gen_random_uuid(), :e, 1, 'PENDING', NOW() - interval '1 hour' "
                    + "+ (g * interval '1 second'), NOW() - interval '2 hours' FROM generate_series(1, 200) g "
                    + "RETURNING id").param("e", expiredEvent).query(UUID.class).list();
            jdbc.sql("UPDATE events SET available = available - 200 WHERE id = :e").param("e", expiredEvent).update();

            // reservas validas, canceladas via HTTP nas duas instancias ao mesmo tempo
            UUID liveEvent = newEvent(100);
            List<UUID> liveIds = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                Resp r = reserve(jobs.baseUrl(i % 2), liveEvent, "mi-live-" + UUID.randomUUID(), 1);
                assertThat(r.status()).isEqualTo(201);
                liveIds.add(UUID.fromString(r.body().get("id").asText()));
            }
            List<Callable<Resp>> cancels = new ArrayList<>();
            for (int i = 0; i < liveIds.size(); i++) {
                String url = jobs.baseUrl(i % 2);
                UUID id = liveIds.get(i);
                cancels.add(() -> cancel(url, id));
            }
            runConcurrently(cancels).forEach(r -> assertThat(r.status()).isEqualTo(204));

            // aguarda os jobs terminarem
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline
                    && count("SELECT COUNT(*) FROM reservations WHERE event_id = ? AND status = 'EXPIRED'",
                            expiredEvent) < 200) {
                Thread.sleep(100);
            }

            assertThat(count("SELECT COUNT(*) FROM reservations WHERE event_id = ? AND status = 'EXPIRED'",
                    expiredEvent)).isEqualTo(200);
            assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE event_id = ? AND action = 'EXPIRED'",
                    expiredEvent)).isEqualTo(200);
            assertThat(count("SELECT COUNT(DISTINCT reservation_id) FROM reservation_history WHERE event_id = ? "
                    + "AND action = 'EXPIRED'", expiredEvent)).isEqualTo(200);
            assertThat(expiredIds).hasSize(200);
            assertThat(StockInvariant.available(jdbc, expiredEvent)).isEqualTo(200);
            StockInvariant.assertHolds(jdbc, expiredEvent);

            assertThat(count("SELECT COUNT(*) FROM reservations WHERE event_id = ? AND status = 'CANCELLED'",
                    liveEvent)).isEqualTo(60);
            assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE event_id = ? AND action = 'CANCELLED'",
                    liveEvent)).isEqualTo(60);
            assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE event_id = ? AND action = 'EXPIRED'",
                    liveEvent)).isZero();
            assertThat(StockInvariant.available(jdbc, liveEvent)).isEqualTo(100);
            StockInvariant.assertHolds(jdbc, liveEvent);

            Map<String, Integer> byInstance = new TreeMap<>();
            jdbc.sql("SELECT instance_id, COUNT(*) AS n FROM reservation_history WHERE event_id = :e "
                    + "AND action = 'EXPIRED' GROUP BY instance_id").param("e", expiredEvent)
                    .query((rs, i) -> byInstance.put(rs.getString(1), rs.getInt(2))).list();
            System.out.println("[MultiInstance] EXPIRED rows by instance_id: " + byInstance);
            assertThat(byInstance.keySet()).isSubsetOf("api1", "api2");
        }
    }
}
