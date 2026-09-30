package com.flashbooking.service.impl;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashbooking.config.BookingProperties;
import com.flashbooking.exception.BusinessException;
import com.flashbooking.infra.AuditContext;
import com.flashbooking.infra.TxSupport;
import com.flashbooking.model.domain.IdempotencyKey;
import com.flashbooking.model.domain.Reservation;
import com.flashbooking.model.dto.request.CreateReservationRequest;
import com.flashbooking.model.dto.response.ReservationResponse;
import com.flashbooking.model.enums.ErrorCode;
import com.flashbooking.model.enums.HistoryAction;
import com.flashbooking.model.enums.ReservationStatus;
import com.flashbooking.repository.EventRepository;
import com.flashbooking.repository.ReservationHistoryRepository;
import com.flashbooking.repository.ReservationRepository;
import com.flashbooking.service.IIdempotencyService;
import com.flashbooking.service.IReservationService;

import io.micrometer.core.instrument.MeterRegistry;

@Service
public class ReservationService implements IReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private static final int MAX_KEY_LENGTH = 150;
    private static final int HTTP_CREATED = 201;

    private final ReservationRepository reservationRepository;
    private final ReservationHistoryRepository historyRepository;
    private final EventRepository eventRepository;
    private final IIdempotencyService idempotencyService;
    private final TxSupport txSupport;
    private final AuditContext audit;
    private final BookingProperties props;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;
    private final TransactionTemplate tx;

    public ReservationService(ReservationRepository reservationRepository,
            ReservationHistoryRepository historyRepository, EventRepository eventRepository,
            IIdempotencyService idempotencyService, TxSupport txSupport, AuditContext audit,
            BookingProperties props, ObjectMapper objectMapper, MeterRegistry meters,
            PlatformTransactionManager txManager) {
        this.reservationRepository = reservationRepository;
        this.historyRepository = historyRepository;
        this.eventRepository = eventRepository;
        this.idempotencyService = idempotencyService;
        this.txSupport = txSupport;
        this.audit = audit;
        this.props = props;
        this.objectMapper = objectMapper;
        this.meters = meters;
        // READ COMMITTED (padrao do PostgreSQL); TransactionTemplate para validar ANTES de abrir a transacao
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public ReservationResult create(UUID eventId, String idempotencyKey, CreateReservationRequest request) {
        // 0. validacoes fora da transacao
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_IDEMPOTENCY_KEY);
        }
        Integer quantity = request != null ? request.quantity() : null;
        if (quantity == null || quantity < 1 || quantity > props.reservation().maxQuantity()) {
            throw new BusinessException(ErrorCode.INVALID_QUANTITY,
                    "Quantity must be between 1 and " + props.reservation().maxQuantity());
        }
        String hash = idempotencyService.requestHash(eventId, quantity);
        log.info("reservation requested event={} quantity={}", eventId, quantity);

        try {
            return tx.execute(status -> doCreate(eventId, idempotencyKey, hash, quantity));
        } catch (BusinessException ex) {
            if (ex.getCode() == ErrorCode.INSUFFICIENT_CAPACITY) {
                meters.counter("reservations.rejected", "reason", "insufficient_capacity").increment();
            } else if (ex.getCode() == ErrorCode.IDEMPOTENCY_KEY_CONFLICT) {
                meters.counter("reservations.rejected", "reason", "idempotency_conflict").increment();
            }
            throw ex;
        }
    }

    private ReservationResult doCreate(UUID eventId, String key, String hash, int quantity) {
        txSupport.applyTimeouts();

        // 1. chave de idempotencia (o INSERT espera a tx concorrente com a mesma chave)
        var replay = idempotencyService.begin(key, hash);
        if (replay.isPresent()) {
            return replayOf(replay.get());
        }

        // 2-4. reserva com timestamps do banco
        UUID id = UUID.randomUUID();
        double ttlSeconds = props.reservation().ttl().toMillis() / 1000.0;
        Reservation reservation = reservationRepository.insert(id, eventId, quantity, ttlSeconds);
        ReservationResponse response = ReservationResponse.from(reservation);

        // 5. historico CREATED na mesma transacao
        historyRepository.insert(id, eventId, HistoryAction.CREATED, null, ReservationStatus.PENDING, quantity,
                "CLIENT_REQUEST", audit.correlationId(), audit.instanceId());

        // 6. resposta salva junto da chave
        idempotencyService.complete(key, HTTP_CREATED, toJson(response));

        // 7. hot row por ultimo: 0 linhas = sem estoque (o evento existe, a FK do passo 3 passou)
        if (eventRepository.decrementIfAvailable(eventId, quantity) == 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_CAPACITY);
        }
        log.info("capacity acquired reservation={} event={} quantity={}", id, eventId, quantity);

        // contabiliza somente apos o commit efetivo
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                meters.counter("reservations.created").increment();
                log.info("commit completed reservation={}", id);
            }
        });
        return new ReservationResult(response, HTTP_CREATED, false);
    }

    private ReservationResult replayOf(IdempotencyKey saved) {
        try {
            ReservationResponse body = objectMapper.readValue(saved.responseJson(), ReservationResponse.class);
            log.info("idempotent replay reservation={}", body.id());
            return new ReservationResult(body, saved.httpStatus(), true);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored idempotent response is unreadable", e);
        }
    }

    private String toJson(ReservationResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public ReservationResponse getById(UUID id) {
        return reservationRepository.findById(id)
                .map(ReservationResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
    }
}
