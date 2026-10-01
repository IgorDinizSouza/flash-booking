package com.flashbooking.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import com.flashbooking.support.AppInstances;
import com.flashbooking.support.AppInstances.Db;
import com.flashbooking.support.StockInvariant;

/** EXP-12: reinicio da aplicacao com reservas vencidas pendentes (a app parada nao expira nada; a nova expira tudo). */
class ExpirationRestartTest {

    @Test
    @DisplayName("EXP-12 reinicio com vencidas pendentes: o primeiro ciclo apos a subida expira todas e devolve o estoque")
    void restartedApplicationDrainsOverdueReservations() throws Exception {
        Db db = AppInstances.newDatabase();
        UUID eventId;
        List<UUID> dueIds = new ArrayList<>();
        UUID liveId;

        // 1a vida da aplicacao (job desligado, como se tivesse caido): reservas reais criadas via HTTP
        try (ConfigurableApplicationContext first = AppInstances.start(db, "api1",
                "--booking.reservation.expiration-job-enabled=false")) {
            eventId = AppInstances.newEvent(db, 50);
            String base = AppInstances.baseUrl(first);
            for (int i = 0; i < 30; i++) {
                var r = AppInstances.reserve(base, eventId, "restart-" + i, 1);
                assertThat(r.status()).isEqualTo(201);
                dueIds.add(UUID.fromString(r.body().get("id").asText()));
            }
            var live = AppInstances.reserve(base, eventId, "restart-live", 2);
            liveId = UUID.fromString(live.body().get("id").asText());
        }

        // com a app PARADA o tempo passa: as 30 reservas vencem (o relogio do banco segue andando)
        db.jdbc().sql("UPDATE reservations SET created_at = NOW() - interval '2 hours', "
                + "expires_at = NOW() - interval '1 hour' WHERE id = ANY(:ids)")
                .param("ids", dueIds.toArray(new UUID[0])).update();
        assertThat(StockInvariant.available(db.jdbc(), eventId)).isEqualTo(18);
        UUID event = eventId;

        // 2a vida: a aplicacao sobe com o job ligado e drena o backlog sozinha
        try (ConfigurableApplicationContext second = AppInstances.start(db, "api2",
                "--booking.reservation.expiration-job-enabled=true", "--booking.reservation.expiration-job-delay=200ms",
                "--booking.reservation.expiration-batch-size=7")) {
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                    assertThat(db.jdbc().sql("SELECT COUNT(*) FROM reservations WHERE event_id = :e "
                            + "AND status = 'PENDING' AND expires_at <= NOW()").param("e", event)
                            .query(Integer.class).single()).isZero());

            assertThat(db.jdbc().sql("SELECT COUNT(*) FROM reservations WHERE event_id = :e AND status = 'EXPIRED'")
                    .param("e", event).query(Integer.class).single()).isEqualTo(30);
            assertThat(db.jdbc().sql("SELECT status FROM reservations WHERE id = :id").param("id", liveId)
                    .query(String.class).single()).isEqualTo("PENDING");
            assertThat(StockInvariant.available(db.jdbc(), event)).isEqualTo(48);
            assertThat(db.jdbc().sql("SELECT COUNT(*) FROM reservation_history WHERE event_id = :e "
                    + "AND action = 'EXPIRED' AND instance_id = 'api2' AND correlation_id LIKE 'job-%'")
                    .param("e", event).query(Integer.class).single()).isEqualTo(30);
            StockInvariant.assertHolds(db.jdbc(), event);
            // e a propria API reporta o estado final
            assertThat(AppInstances.get(AppInstances.baseUrl(second), "/reservations/" + dueIds.get(0)).body()
                    .get("status").asText()).isEqualTo("EXPIRED");
        }
    }
}
