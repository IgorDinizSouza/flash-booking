package com.flashbooking.repository;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.flashbooking.model.enums.HistoryAction;
import com.flashbooking.model.enums.ReservationStatus;

/** Tabela append-only: so INSERT, dentro da mesma transacao da mudanca de estado. SQL em resources/mapper. */
@Mapper
public interface ReservationHistoryRepository {

    /** Maximo de linhas por INSERT multi-row (9 parametros por linha, bem abaixo do limite de 32767 do driver). */
    int BATCH_CHUNK = 1000;

    /** Uma linha de historico de um lote: acao/estados/motivo sao comuns a todas. */
    record Entry(UUID reservationId, UUID eventId, int quantity) {
    }

    /** Insert em lote (uma linha por reserva) na transacao corrente, em fatias de {@link #BATCH_CHUNK}. */
    default void insertBatch(List<Entry> entries, HistoryAction action, ReservationStatus previousStatus,
            ReservationStatus newStatus, String reason, String correlationId, String instanceId) {
        for (int from = 0; from < entries.size(); from += BATCH_CHUNK) {
            insertRows(entries.subList(from, Math.min(from + BATCH_CHUNK, entries.size())), action,
                    previousStatus, newStatus, reason, correlationId, instanceId);
        }
    }

    void insertRows(@Param("entries") List<Entry> entries, @Param("action") HistoryAction action,
            @Param("previousStatus") ReservationStatus previousStatus,
            @Param("newStatus") ReservationStatus newStatus, @Param("reason") String reason,
            @Param("correlationId") String correlationId, @Param("instanceId") String instanceId);

    void insert(@Param("reservationId") UUID reservationId, @Param("eventId") UUID eventId,
            @Param("action") HistoryAction action, @Param("previousStatus") ReservationStatus previousStatus,
            @Param("newStatus") ReservationStatus newStatus, @Param("quantity") int quantity,
            @Param("reason") String reason, @Param("correlationId") String correlationId,
            @Param("instanceId") String instanceId);
}
