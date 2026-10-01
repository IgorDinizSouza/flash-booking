package com.flashbooking;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;

import com.flashbooking.service.IEventService;
import com.flashbooking.service.IReservationService;
import com.flashbooking.service.impl.EventService;
import com.flashbooking.service.impl.ReservationService;

/** Regras de arquitetura verificadas por inspecao do codigo-fonte/reflexao (sem Spring nem banco). */
class ArchitectureRulesTest {

    private static final Path MAIN = Path.of("src", "main");

    private static List<Path> files(String glob) throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(glob)).toList();
        }
    }

    private static List<String> offenders(List<Path> files, Pattern pattern) throws IOException {
        List<String> out = new ArrayList<>();
        for (Path p : files) {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            var m = pattern.matcher(text);
            if (m.find()) {
                out.add(p + " -> " + m.group().replaceAll("\\s+", " "));
            }
        }
        return out;
    }

    @Test
    @DisplayName("HST-09 nenhum SQL/codigo de producao altera ou apaga reservation_history (append-only)")
    void noProductionCodeUpdatesOrDeletesHistory() throws IOException {
        var sources = new ArrayList<>(files(".java"));
        sources.addAll(files(".xml"));
        sources.addAll(files(".sql"));
        assertThat(sources).isNotEmpty();
        Pattern writes = Pattern.compile("(?is)(UPDATE\\s+(ONLY\\s+)?reservation_history\\b|DELETE\\s+FROM\\s+(ONLY\\s+)?"
                + "reservation_history\\b|TRUNCATE\\s+(TABLE\\s+)?reservation_history\\b)");

        assertThat(offenders(sources, writes)).isEmpty();
        // e o unico INSERT existente e o da propria tabela (caminho de escrita esperado)
        assertThat(offenders(files(".xml"), Pattern.compile("(?is)INSERT\\s+INTO\\s+reservation_history"))).hasSize(1);
    }

    @Test
    @DisplayName("MUL-10 nenhuma regra usa o relogio da aplicacao: tempo vem so do NOW() do banco")
    void applicationClockIsNeverUsed() throws IOException {
        Pattern clock = Pattern.compile("Instant\\.now|LocalDateTime\\.now|LocalDate\\.now|OffsetDateTime\\.now"
                + "|ZonedDateTime\\.now|System\\.currentTimeMillis|System\\.nanoTime|new\\s+Date\\(|Clock\\.system"
                + "|Calendar\\.getInstance");

        assertThat(offenders(files(".java"), clock)).isEmpty();
        assertThat(offenders(files(".xml"), Pattern.compile("(?i)CURRENT_TIMESTAMP|clock_timestamp"))).isEmpty();
    }

    @Test
    @DisplayName("QRY-09 a leitura de reserva nao passa por cache (sem @Cacheable em reserva); so GET /events e cacheado")
    void reservationReadsAreNeverCached() {
        for (Class<?> type : List.of(IReservationService.class, ReservationService.class)) {
            assertThat(type.isAnnotationPresent(Cacheable.class)).as(type.getSimpleName()).isFalse();
            for (Method m : type.getDeclaredMethods()) {
                assertThat(m.isAnnotationPresent(Cacheable.class)).as(type.getSimpleName() + "#" + m.getName())
                        .isFalse();
            }
        }
        long cached = 0;
        for (Method m : EventService.class.getDeclaredMethods()) {
            cached += m.isAnnotationPresent(Cacheable.class) ? 1 : 0;
        }
        assertThat(cached).as("somente EventService#getById e @Cacheable").isEqualTo(1);
        assertThat(IEventService.class).isNotNull();
    }
}
