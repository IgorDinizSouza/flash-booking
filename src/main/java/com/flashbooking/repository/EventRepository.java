package com.flashbooking.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flashbooking.model.domain.Event;

@Repository
public class EventRepository {

    private static final String COLUMNS = "id, name, total_capacity, available, created_at";

    private final JdbcClient jdbc;

    public EventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Event insert(String name, int capacity) {
        return jdbc.sql("INSERT INTO events (name, total_capacity, available) VALUES (:name, :capacity, :capacity) "
                + "RETURNING " + COLUMNS)
                .param("name", name)
                .param("capacity", capacity)
                .query(EventRepository::map)
                .single();
    }

    public Optional<Event> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM events WHERE id = :id")
                .param("id", id)
                .query(EventRepository::map)
                .optional();
    }

    /**
     * Baixa atomica de estoque (sem SELECT previo). Como eventos nunca sao removidos, 0 linhas
     * significa apenas estoque insuficiente quando o evento ja foi validado por FK.
     *
     * @return linhas afetadas (1 = baixou, 0 = sem estoque suficiente)
     */
    public int decrementIfAvailable(UUID eventId, int quantity) {
        return jdbc.sql("UPDATE events SET available = available - :q WHERE id = :id AND available >= :q")
                .param("q", quantity)
                .param("id", eventId)
                .update();
    }

    private static Event map(ResultSet rs, int rowNum) throws SQLException {
        return new Event(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getInt("total_capacity"),
                rs.getInt("available"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
