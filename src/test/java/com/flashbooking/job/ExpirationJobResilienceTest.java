package com.flashbooking.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import com.flashbooking.support.AbstractApiTest;
import com.flashbooking.support.DbLock;
import com.flashbooking.support.StockInvariant;

/**
 * Job LIGADO (ciclo de 200 ms, TTL 2 s, lock_timeout 300 ms) em contexto proprio: falha real do ciclo nao derruba o
 * scheduler (EXP-11/INF-20) e o TTL configuravel expira a reserva sem tocar no banco (EXP-13).
 */
@TestPropertySource(properties = {
        "booking.reservation.expiration-job-enabled=true",
        "booking.reservation.expiration-job-delay=200ms",
        "booking.reservation.ttl=2s",
        "booking.db.lock-timeout=300ms"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExpirationJobResilienceTest extends AbstractApiTest {

    @Autowired
    DataSource dataSource;

    @Test
    @DisplayName("EXP-11/INF-20 falha real no ciclo (lock timeout na linha do evento) nao derruba o scheduler; ciclo seguinte recupera")
    void failingCycleDoesNotStopTheSchedulerAndNextCycleRecovers() {
        UUID eventId = newEvent(10);
        UUID id = reserveOk(eventId, 4);
        assertThat(available(eventId)).isEqualTo(6);

        Logger jobLogger = (Logger) LoggerFactory.getLogger(ReservationExpirationJob.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        jobLogger.addAppender(appender);
        try (DbLock ignored = DbLock.lockEvent(dataSource, eventId)) {
            // a reserva vence, mas o UPDATE do estoque espera a linha quente travada e estoura o lock_timeout: o ciclo
            // lanca; o job precisa registrar, continuar vivo e tentar de novo (varios ciclos com falha)
            expireInPast(id);
            await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(
                    () -> assertThat(appender.list.stream()
                            .filter(e -> e.getFormattedMessage().contains("expiration job cycle failed")).count())
                            .isGreaterThanOrEqualTo(3));
            assertThat(physicalStatus(id)).as("rollback do ciclo falho").isEqualTo("PENDING");
            assertThat(historyCount(id, "EXPIRED")).isZero();
            assertThat(available(eventId)).isEqualTo(6);
        } finally {
            jobLogger.detachAppender(appender);
        }

        // lock liberado: o proximo ciclo expira, devolve o estoque e grava o historico uma unica vez
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(physicalStatus(id)).isEqualTo("EXPIRED"));
        assertThat(available(eventId)).isEqualTo(10);
        assertThat(historyCount(id, "EXPIRED")).isEqualTo(1);
        StockInvariant.assertHolds(jdbc, eventId);

        // o scheduler continua vivo depois da recuperacao: uma nova reserva vencida tambem e expirada
        UUID next = reserveOk(eventId, 1);
        expireInPast(next);
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(physicalStatus(next)).isEqualTo("EXPIRED"));
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    @DisplayName("EXP-13 TTL configuravel (2s): expiresAt = createdAt + 2s e o job expira sozinho, sem tocar no banco")
    void configurableTtlExpiresByItself() {
        UUID eventId = newEvent(5);

        var res = postReservation(eventId, newKey(), 5);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Instant createdAt = Instant.parse(res.getBody().get("createdAt").asText());
        Instant expiresAt = Instant.parse(res.getBody().get("expiresAt").asText());
        assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofSeconds(2));
        UUID id = UUID.fromString(res.getBody().get("id").asText());
        assertThat(available(eventId)).isZero();
        assertThat(postReservation(eventId, newKey(), 1).getStatusCode()).as("evento esgotado").isEqualTo(HttpStatus.CONFLICT);

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(physicalStatus(id)).isEqualTo("EXPIRED"));

        assertThat(available(eventId)).isEqualTo(5);
        assertThat(get(id).getBody().get("status").asText()).isEqualTo("EXPIRED");
        assertThat(delete(id).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(historyCount(id, "EXPIRED")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT correlation_id FROM reservation_history WHERE reservation_id = :id "
                + "AND action = 'EXPIRED'").param("id", id).query(String.class).single()).startsWith("job-");
        assertThat(postReservation(eventId, newKey(), 1).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
