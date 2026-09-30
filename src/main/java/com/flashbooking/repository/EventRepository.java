package com.flashbooking.repository;

import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.flashbooking.model.domain.Event;

/** SQL em resources/mapper/EventRepository.xml. */
@Mapper
public interface EventRepository {

    Event insert(@Param("name") String name, @Param("capacity") int capacity);

    Optional<Event> findById(@Param("id") UUID id);

    /**
     * Baixa atomica de estoque (sem SELECT previo). Como eventos nunca sao removidos, 0 linhas
     * significa apenas estoque insuficiente quando o evento ja foi validado por FK.
     *
     * @return linhas afetadas (1 = baixou, 0 = sem estoque suficiente)
     */
    int decrementIfAvailable(@Param("eventId") UUID eventId, @Param("quantity") int quantity);

    /**
     * Devolucao de estoque (cancelamento/expiracao). O CHECK available <= total_capacity e a rede de
     * seguranca: se a invariante quebrar, o UPDATE falha alto (DataIntegrityViolationException).
     *
     * @return linhas afetadas
     */
    int increment(@Param("eventId") UUID eventId, @Param("quantity") int quantity);
}
