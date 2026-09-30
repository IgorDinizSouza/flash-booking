package com.flashbooking.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flashbooking.service.IReservationExpirationService;

/** Roda em todas as instancias; SKIP LOCKED no service divide o trabalho entre elas. */
@Component
@ConditionalOnProperty(name = "booking.reservation.expiration-job-enabled", havingValue = "true",
        matchIfMissing = true)
public class ReservationExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirationJob.class);
    private static final int MAX_ITERATIONS = 20;

    private final IReservationExpirationService expirationService;

    public ReservationExpirationJob(IReservationExpirationService expirationService) {
        this.expirationService = expirationService;
    }

    // @Scheduled nao entende "5s"/"200ms" (so ms ou ISO-8601): converte com o DurationStyle do Boot, o mesmo do @ConfigurationProperties
    @Scheduled(fixedDelayString = "#{T(org.springframework.boot.convert.DurationStyle).detectAndParse('${booking.reservation.expiration-job-delay}').toMillis()}")
    public void run() {
        try {
            expirationService.expireAll(MAX_ITERATIONS);
        } catch (Exception ex) {
            // DB ocupado (lock/statement timeout, deadlock, pool esgotado) ou qualquer falha: o proximo ciclo tenta de novo
            log.warn("expiration job cycle failed, will retry next cycle: {}", ex.toString());
        }
    }
}
