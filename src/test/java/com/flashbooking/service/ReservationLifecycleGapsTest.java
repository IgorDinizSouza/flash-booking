package com.flashbooking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import com.flashbooking.support.AbstractApiTest;
import com.flashbooking.support.DbLock;
import com.flashbooking.support.StockInvariant;

/** Cancelamento/expiracao/consulta: lacunas CAN-17, EXP-15/16/17, QRY-08 e MUL-09 (linha travada por DELETE em voo). */
class ReservationLifecycleGapsTest extends AbstractApiTest {

    @Autowired
    IReservationExpirationService expiration;

    @Autowired
    DataSource dataSource;

    @Test
    @DisplayName("CAN-17 DELETE repetido em reserva CANCELLED cujo prazo ja passou: 204, o job nao a expira")
    void repeatedDeleteOnCancelledReservationPastItsExpiry() {
        expiration.expireAll(1000);
        UUID eventId = newEvent(5);
        UUID id = reserveOk(eventId, 3);
        assertThat(delete(id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        expireInPast(id); // o prazo original "passou" depois do cancelamento
        assertThat(expiration.expireAll(1000)).isZero();

        assertThat(delete(id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(physicalStatus(id)).isEqualTo("CANCELLED");
        assertThat(get(id).getBody().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(historyCount(id, "EXPIRED")).isZero();
        assertThat(historyCount(id, "CANCELLED")).isEqualTo(1);
        assertThat(available(eventId)).isEqualTo(5);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    @DisplayName("EXP-15 o job nao toca reservas CANCELLED nem EXPIRED (sem nova linha, sem devolver estoque de novo)")
    void jobIgnoresCancelledAndAlreadyExpiredReservations() {
        expiration.expireAll(1000);
        UUID eventId = newEvent(10);
        UUID cancelled = reserveOk(eventId, 2);
        UUID expired = reserveOk(eventId, 3);
        assertThat(delete(cancelled).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        expireInPast(cancelled);
        expireInPast(expired);
        assertThat(expiration.expireAll(1000)).isEqualTo(1);
        assertThat(available(eventId)).isEqualTo(10);

        assertThat(expiration.expireAll(1000)).isZero();

        assertThat(physicalStatus(cancelled)).isEqualTo("CANCELLED");
        assertThat(physicalStatus(expired)).isEqualTo("EXPIRED");
        assertThat(historyCount(cancelled, "EXPIRED")).isZero();
        assertThat(historyCount(expired, "EXPIRED")).isEqualTo(1);
        assertThat(available(eventId)).isEqualTo(10);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    @DisplayName("EXP-16 o estoque so volta depois do job: antes 409 (GET ja diz EXPIRED), depois 201")
    void stockComesBackOnlyAfterTheJob() {
        expiration.expireAll(1000);
        UUID eventId = newEvent(1);
        UUID id = reserveOk(eventId, 1);
        expireInPast(id);

        assertThat(get(id).getBody().get("status").asText()).isEqualTo("EXPIRED");
        assertThat(physicalStatus(id)).isEqualTo("PENDING");
        assertProblem(postReservation(eventId, newKey(), 1), HttpStatus.CONFLICT, "INSUFFICIENT_CAPACITY");

        assertThat(expiration.expireAll(1000)).isEqualTo(1);

        assertThat(postReservation(eventId, newKey(), 1).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(available(eventId)).isZero();
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    @DisplayName("EXP-17/MUL-09 reserva travada por DELETE em andamento e ignorada (SKIP LOCKED) e expirada no ciclo seguinte")
    void lockedReservationIsSkippedThenExpiredOnTheNextCycle() {
        expiration.expireAll(1000);
        UUID eventId = newEvent(5);
        UUID id = reserveOk(eventId, 2);
        expireInPast(id);

        try (DbLock ignored = DbLock.lockReservation(dataSource, id)) {
            long start = System.nanoTime();
            assertThat(expiration.expireBatch()).as("linha travada e pulada").isZero();
            assertThat((System.nanoTime() - start) / 1_000_000).as("sem esperar o lock (ms)").isLessThan(2_000);
            assertThat(physicalStatus(id)).isEqualTo("PENDING");
            assertThat(available(eventId)).isEqualTo(3);
        }

        assertThat(expiration.expireBatch()).isEqualTo(1);
        assertThat(physicalStatus(id)).isEqualTo("EXPIRED");
        assertThat(available(eventId)).isEqualTo(5);
        assertThat(historyCount(id, "EXPIRED")).isEqualTo(1);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    @DisplayName("QRY-08 fronteira exata: expires_at == agora (<=) e reportada como EXPIRED, sem devolver estoque")
    void reservationExpiringExactlyNowIsEffectivelyExpired() {
        expiration.expireAll(1000);
        UUID eventId = newEvent(5);
        UUID id = reserveOk(eventId, 2);
        jdbc.sql("UPDATE reservations SET created_at = NOW() - interval '10 minutes', expires_at = NOW() "
                + "WHERE id = :id").param("id", id).update();

        var res = get(id);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("status").asText()).isEqualTo("EXPIRED");
        assertThat(physicalStatus(id)).isEqualTo("PENDING");
        assertThat(available(eventId)).isEqualTo(3);
        assertThat(historyCount(id, "EXPIRED")).isZero();
        expiration.expireAll(1000);
        assertThat(physicalStatus(id)).isEqualTo("EXPIRED");
    }
}
