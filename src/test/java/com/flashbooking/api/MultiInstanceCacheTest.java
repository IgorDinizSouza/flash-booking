package com.flashbooking.api;

import static com.flashbooking.support.AppInstances.cancel;
import static com.flashbooking.support.AppInstances.get;
import static com.flashbooking.support.AppInstances.reserve;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import com.flashbooking.support.AppInstances;
import com.flashbooking.support.AppInstances.Db;
import com.flashbooking.support.AppInstances.Resp;
import com.flashbooking.support.StockInvariant;

/**
 * Duas instancias completas com cache de disponibilidade LIGADO (TTL 3s) sobre o mesmo banco: a leitura de
 * /events/{id} pode divergir entre instancias por ate o TTL e depois converge (consistencia eventual, RNF5);
 * a reserva nao tem cache: qualquer instancia enxerga o estado atual.
 */
class MultiInstanceCacheTest {

    private static Db db;
    private static ConfigurableApplicationContext ctxA;
    private static ConfigurableApplicationContext ctxB;
    private static String a;
    private static String b;

    @BeforeAll
    static void start() throws Exception {
        db = AppInstances.newDatabase();
        String[] args = { "--booking.reservation.expiration-job-enabled=false",
                "--booking.availability-cache.enabled=true", "--booking.availability-cache.ttl=3s" };
        // A migra primeiro; B sobe depois sobre o schema pronto
        ctxA = AppInstances.start(db, "api1", args);
        ctxB = AppInstances.start(db, "api2", args);
        a = AppInstances.baseUrl(ctxA);
        b = AppInstances.baseUrl(ctxB);
    }

    @AfterAll
    static void stop() {
        try {
            if (ctxA != null) {
                ctxA.close();
            }
        } finally {
            if (ctxB != null) {
                ctxB.close();
            }
        }
    }

    private static int availableOn(String base, UUID eventId) throws Exception {
        Resp r = get(base, "/events/" + eventId);
        assertThat(r.status()).isEqualTo(200);
        return r.body().get("available").asInt();
    }

    @Test
    @DisplayName("MUL-05 cache divergente entre instancias: A mostra o valor antigo ate o TTL, B o novo, ambas convergem")
    void cachesDivergeTemporarilyThenConverge() throws Exception {
        UUID eventId = AppInstances.newEvent(db, 50);

        assertThat(availableOn(a, eventId)).isEqualTo(50); // popula o cache de A

        Resp created = reserve(b, eventId, "mc-" + UUID.randomUUID(), 2);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.instance()).isEqualTo("api2");

        // dentro do TTL: B (cache frio) le o banco e ve 48; A segue com o valor em cache
        assertThat(availableOn(b, eventId)).isEqualTo(48);
        assertThat(availableOn(a, eventId)).as("instancia A ainda serve o valor antigo").isEqualTo(50);
        assertThat(StockInvariant.available(db.jdbc(), eventId)).isEqualTo(48);

        // apos o TTL, A converge (oscilacao 50 -> 48 esperada)
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(availableOn(a, eventId)).isEqualTo(48));
        assertThat(availableOn(b, eventId)).isEqualTo(48);
    }

    @Test
    @DisplayName("MUL-06/QRY-11 reserva criada em A e lida/cancelada em B; A enxerga o cancelamento na hora (sem cache)")
    void reservationIsReadAndCancelledOnTheOtherInstance() throws Exception {
        UUID eventId = AppInstances.newEvent(db, 10);
        Resp created = reserve(a, eventId, "mr-" + UUID.randomUUID(), 3);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.instance()).isEqualTo("api1");
        UUID id = UUID.fromString(created.body().get("id").asText());

        Resp readOnB = get(b, "/reservations/" + id);
        assertThat(readOnB.status()).isEqualTo(200);
        assertThat(readOnB.instance()).isEqualTo("api2");
        assertThat(readOnB.body()).isEqualTo(created.body());

        Resp cancelOnB = cancel(b, id);
        assertThat(cancelOnB.status()).isEqualTo(204);
        Resp readOnA = get(a, "/reservations/" + id);
        assertThat(readOnA.status()).isEqualTo(200);
        assertThat(readOnA.body().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancel(a, id).status()).as("DELETE repetido em outra instancia").isEqualTo(204);

        assertThat(StockInvariant.available(db.jdbc(), eventId)).isEqualTo(10);
        assertThat(db.jdbc().sql("SELECT COUNT(*) FROM reservation_history WHERE reservation_id = :id")
                .param("id", id).query(Integer.class).single()).isEqualTo(2);
        StockInvariant.assertHolds(db.jdbc(), eventId);
    }
}
