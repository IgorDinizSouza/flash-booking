package com.flashbooking.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashbooking.FlashBookingApplication;

/**
 * Sobe aplicacoes Spring completas (porta aleatoria) contra um banco NOVO do PostgreSQL de teste, para cenarios que
 * precisam de varias instancias/reinicio sem interferir nos outros testes (mesmo padrao do MultiInstanceConcurrencyTest).
 */
public final class AppInstances {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    public record Resp(int status, JsonNode body, String instance, String replayed) {
    }

    /** Banco novo + JdbcClient para semear/inspecionar. */
    public record Db(String url, JdbcClient jdbc) {
    }

    private AppInstances() {
    }

    public static Db newDatabase() throws Exception {
        var pg = AbstractIntegrationTest.POSTGRES;
        String name = "inst_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        String url = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + name;
        return new Db(url, JdbcClient.create(new DriverManagerDataSource(url, pg.getUsername(), pg.getPassword())));
    }

    /** Sobe uma instancia; {@code extraArgs} no formato "--chave=valor". */
    public static ConfigurableApplicationContext start(Db db, String instanceId, String... extraArgs) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--INSTANCE_ID=" + instanceId,
                "--spring.datasource.url=" + db.url(),
                "--spring.datasource.username=" + AbstractIntegrationTest.POSTGRES.getUsername(),
                "--spring.datasource.password=" + AbstractIntegrationTest.POSTGRES.getPassword(),
                "--spring.datasource.hikari.minimum-idle=2"));
        args.addAll(List.of(extraArgs));
        return new SpringApplicationBuilder(FlashBookingApplication.class).profiles("test")
                .run(args.toArray(new String[0]));
    }

    public static String baseUrl(ConfigurableApplicationContext ctx) {
        return "http://localhost:" + ctx.getEnvironment().getProperty("local.server.port");
    }

    public static UUID newEvent(Db db, int capacity) {
        return db.jdbc().sql("INSERT INTO events (name, total_capacity, available) VALUES (:n, :c, :c) RETURNING id")
                .param("n", "Inst " + UUID.randomUUID()).param("c", capacity).query(UUID.class).single();
    }

    public static Resp send(HttpRequest.Builder req) throws Exception {
        HttpResponse<String> r = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode body = r.body() == null || r.body().isBlank() ? null : JSON.readTree(r.body());
        return new Resp(r.statusCode(), body, r.headers().firstValue("X-Instance-Id").orElse(null),
                r.headers().firstValue("Idempotent-Replayed").orElse(null));
    }

    public static Resp get(String baseUrl, String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET());
    }

    public static Resp reserve(String baseUrl, UUID eventId, String key, int quantity) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/events/" + eventId + "/reservations"))
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString("{\"quantity\":" + quantity + "}")));
    }

    public static Resp cancel(String baseUrl, UUID id) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/reservations/" + id)).DELETE());
    }
}
