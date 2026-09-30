package com.flashbooking.service.impl;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flashbooking.config.BookingProperties;
import com.flashbooking.filter.CorrelationIdFilter;
import com.flashbooking.infra.AuditContext;
import com.flashbooking.infra.TxSupport;
import com.flashbooking.model.enums.HistoryAction;
import com.flashbooking.model.enums.ReservationStatus;
import com.flashbooking.repository.EventRepository;
import com.flashbooking.repository.ReservationHistoryRepository;
import com.flashbooking.repository.ReservationRepository;
import com.flashbooking.service.IReservationExpirationService;

import io.micrometer.core.instrument.MeterRegistry;

@Service
public class ReservationExpirationService implements IReservationExpirationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirationService.class);

    private final ReservationRepository reservationRepository;
    private final ReservationHistoryRepository historyRepository;
    private final EventRepository eventRepository;
    private final TxSupport txSupport;
    private final AuditContext audit;
    private final BookingProperties props;
    private final MeterRegistry meters;
    private final TransactionTemplate tx;

    public ReservationExpirationService(ReservationRepository reservationRepository,
            ReservationHistoryRepository historyRepository, EventRepository eventRepository, TxSupport txSupport,
            AuditContext audit, BookingProperties props, MeterRegistry meters,
            PlatformTransactionManager txManager) {
        this.reservationRepository = reservationRepository;
        this.historyRepository = historyRepository;
        this.eventRepository = eventRepository;
        this.txSupport = txSupport;
        this.audit = audit;
        this.props = props;
        this.meters = meters;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public int expireBatch() {
        return withJobCorrelation(this::expireBatchInTx);
    }

    @Override
    public int expireAll(int maxIterations) {
        return withJobCorrelation(() -> {
            int batchSize = props.reservation().expirationBatchSize();
            int total = 0;
            for (int i = 0; i < maxIterations; i++) {
                int n = expireBatchInTx();
                total += n;
                if (n < batchSize) {
                    break;
                }
            }
            return total;
        });
    }

    /** Correlation id proprio da execucao (job-xxxxxxxx) no MDC; se ja houver um (chamada aninhada), reaproveita. */
    private int withJobCorrelation(java.util.function.IntSupplier body) {
        boolean own = MDC.get(CorrelationIdFilter.MDC_KEY) == null;
        if (own) {
            MDC.put(CorrelationIdFilter.MDC_KEY, "job-" + UUID.randomUUID().toString().substring(0, 8));
        }
        try {
            return body.getAsInt();
        } finally {
            if (own) {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        }
    }

    private int expireBatchInTx() {
        Integer expired = tx.execute(status -> doExpireBatch());
        return expired == null ? 0 : expired;
    }

    private int doExpireBatch() {
        txSupport.applyTimeouts();

        List<ReservationRepository.ExpiredRow> batch =
                reservationRepository.lockExpiredBatch(props.reservation().expirationBatchSize());
        if (batch.isEmpty()) {
            return 0;
        }
        reservationRepository.markExpired(batch.stream().map(ReservationRepository.ExpiredRow::id).toList());
        historyRepository.insertBatch(
                batch.stream().map(r -> new ReservationHistoryRepository.Entry(r.id(), r.eventId(), r.quantity()))
                        .toList(),
                HistoryAction.EXPIRED, ReservationStatus.PENDING, ReservationStatus.EXPIRED, "TTL_EXPIRED",
                audit.correlationId(), audit.instanceId());

        // ordem deterministica por event_id: duas instancias com lotes mistos nunca se travam em ciclo
        Map<UUID, Integer> perEvent = new TreeMap<>();
        for (var r : batch) {
            perEvent.merge(r.eventId(), r.quantity(), Integer::sum);
        }
        // hot rows por ultimo, imediatamente antes do commit
        perEvent.forEach(eventRepository::increment);

        int n = batch.size();
        log.info("expired batch n={} events={}", n, perEvent.size());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                meters.counter("reservations.expired").increment(n);
            }
        });
        return n;
    }
}
