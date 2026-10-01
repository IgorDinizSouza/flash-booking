package com.flashbooking.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;
import com.flashbooking.support.AbstractApiTest;

/**
 * Robustez basica (sem autenticacao no escopo): corpos gigantes/profundos, headers enormes ou com controle, SQL injection,
 * UUID em caixas/formatos diferentes e HTML no nome. Em todos os casos a API segue saudavel e nunca responde 5xx.
 */
class SecurityRobustnessTest extends AbstractApiTest {

    private void assertStillHealthy() {
        assertThat(rest.getForEntity("/actuator/health", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(postEvent("{\"name\":\"alive " + UUID.randomUUID() + "\",\"capacity\":1}").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    // ------------------------------------------------------------------ SEC-01 / SEC-02: tamanho e profundidade

    @ParameterizedTest(name = "SEC-01 corpo de {0} MB em POST /events")
    @ValueSource(ints = { 5, 10 })
    @DisplayName("SEC-01 corpo gigante e rejeitado com 4xx, sem 500 nem OOM")
    void hugeBodyIsRejected(int megabytes) {
        String big = "a".repeat(megabytes * 1024 * 1024);

        var res = postEvent("{\"name\":\"" + big + "\",\"capacity\":5}");

        assertThat(res.getStatusCode().is4xxClientError()).as("status %s", res.getStatusCode()).isTrue();
        if (res.getStatusCode() == HttpStatus.BAD_REQUEST) {
            assertThat(res.getBody().get("code").asText()).isEqualTo("INVALID_EVENT_NAME");
            assertThat(res.getBody().toString().length()).as("a resposta nao ecoa o corpo").isLessThan(2_000);
        }
        assertStillHealthy();
    }

    @Test
    @DisplayName("SEC-01 corpo gigante em POST de reserva (quantity invalida) e rejeitado sem tocar no estoque")
    void hugeBodyOnReservationIsRejected() {
        UUID eventId = newEvent(5);
        String big = "b".repeat(5 * 1024 * 1024);

        var res = postReservation(eventId, newKey(), "{\"quantity\":0,\"pad\":\"" + big + "\"}");

        assertProblem(res, HttpStatus.BAD_REQUEST, "INVALID_QUANTITY");
        assertThat(available(eventId)).isEqualTo(5);
        assertStillHealthy();
    }

    @ParameterizedTest(name = "SEC-02 aninhamento {0}")
    @ValueSource(strings = { "array", "object" })
    @DisplayName("SEC-02 JSON com 5.000 niveis de aninhamento: 400 MALFORMED_REQUEST, sem StackOverflow")
    void deeplyNestedJsonIsMalformed(String kind) {
        int depth = 5_000;
        String body = kind.equals("array") ? "[".repeat(depth) + "]".repeat(depth)
                : "{\"a\":".repeat(depth) + "1" + "}".repeat(depth);

        assertProblem(postEvent(body), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertProblem(postReservation(newEvent(3), newKey(), body), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertStillHealthy();
    }

    @Test
    @DisplayName("SEC-02 campo extra com aninhamento profundo dentro de corpo valido tambem e 400")
    void deeplyNestedExtraFieldIsMalformed() {
        String nested = "[".repeat(3_000) + "]".repeat(3_000);

        assertProblem(postEvent("{\"name\":\"x\",\"capacity\":1,\"extra\":" + nested + "}"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
    }

    // ------------------------------------------------------------------ SEC-03 / SEC-11: headers

    private int rawStatusOfReservationWithKeyLength(int keyLength) throws Exception {
        UUID eventId = newEvent(5);
        var client = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder(URI.create(rest.getRootUri() + "/events/" + eventId + "/reservations"))
                .header("Content-Type", "application/json").header("Idempotency-Key", "k".repeat(keyLength))
                .POST(HttpRequest.BodyPublishers.ofString("{\"quantity\":1}")).build();
        try {
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(available(eventId)).as("nada reservado").isEqualTo(5);
            return res.statusCode();
        } catch (java.io.IOException e) {
            assertThat(available(eventId)).as("nada reservado").isEqualTo(5);
            return -1; // servidor fechou a conexao (limite de header do Tomcat)
        }
    }

    @ParameterizedTest(name = "SEC-03 Idempotency-Key de {0} bytes")
    @ValueSource(ints = { 7_000, 16_000, 65_536 })
    @DisplayName("SEC-03 Idempotency-Key enorme: 4xx (ou conexao fechada), nunca 5xx, e a API segue saudavel")
    void hugeIdempotencyKeyIsRejected(int length) throws Exception {
        int status = rawStatusOfReservationWithKeyLength(length);

        if (status != -1) {
            assertThat(status).as("status").isBetween(400, 499);
        }
        assertStillHealthy();
    }

    @Test
    @DisplayName("SEC-03 chave de 7.000 bytes cabe no header do Tomcat mas excede 150: 400 INVALID_IDEMPOTENCY_KEY")
    void keyBetweenHeaderLimitAndKeyLimitIsInvalidKey() {
        UUID eventId = newEvent(5);

        assertProblem(postReservation(eventId, "k".repeat(7_000), 1), HttpStatus.BAD_REQUEST,
                "INVALID_IDEMPOTENCY_KEY");
        assertThat(available(eventId)).isEqualTo(5);
    }

    /** Envia a requisicao HTTP/1.1 crua (um cliente de verdade nao deixaria montar cabecalho com controle). */
    private String rawHttp(String request) throws Exception {
        URI root = URI.create(rest.getRootUri());
        try (Socket socket = new Socket(root.getHost(), root.getPort())) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            try {
                while ((n = in.read(chunk)) != -1) {
                    buf.write(chunk, 0, n);
                }
            } catch (java.net.SocketTimeoutException ignored) {
                // resposta sem fechar conexao: o que chegou basta
            }
            return buf.toString(StandardCharsets.ISO_8859_1);
        }
    }

    private static int statusOf(String rawResponse) {
        return Integer.parseInt(rawResponse.substring(9, 12));
    }

    @ParameterizedTest(name = "SEC-11 caractere de controle {0} no Idempotency-Key")
    @ValueSource(strings = { "\u0001", "\u0000", "\u007f", "\u001b" })
    @DisplayName("SEC-11 caractere de controle em header: requisicao rejeitada (4xx), nada e gravado")
    void controlCharacterInHeaderIsRejected(String control) throws Exception {
        UUID eventId = newEvent(5);
        String body = "{\"quantity\":1}";
        String request = "POST /events/" + eventId + "/reservations HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: application/json\r\nIdempotency-Key: ab" + control + "cd\r\n"
                + "Connection: close\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;

        String response = rawHttp(request);

        assertThat(statusOf(response)).isBetween(400, 499);
        assertThat(available(eventId)).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key LIKE 'ab%cd'")).isZero();
        assertStillHealthy();
    }

    @Test
    @DisplayName("SEC-11 quebra de linha crua dentro de string JSON (caractere de controle no corpo): 400 MALFORMED_REQUEST")
    void rawControlCharacterInsideJsonStringIsMalformed() {
        assertProblem(postEvent("{\"name\":\"a\nb\",\"capacity\":1}"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertProblem(postEvent("{\"name\":\"a\tb\",\"capacity\":1}"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
    }

    // ------------------------------------------------------------------ SEC-04 / SEC-06: SQL injection

    @ParameterizedTest(name = "SEC-04 name [{0}]")
    @ValueSource(strings = { "x'); DROP TABLE events;--", "'; DELETE FROM events; --", "\\\\'; UPDATE events SET available=0;--",
            "\" OR \"1\"=\"1", "x' OR '1'='1", "Robert'); DROP TABLE reservations;--" })
    @DisplayName("SEC-04 SQL injection no name: gravado literalmente, tabelas intactas")
    void sqlInjectionInNameIsStoredAsText(String payload) {
        String escaped = payload.replace("\\", "\\\\").replace("\"", "\\\"");
        long eventsBefore = count("SELECT COUNT(*) FROM events");

        var res = postEvent("{\"name\":\"" + escaped + "\",\"capacity\":7}");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = res.getBody().get("id").asText();
        assertThat(res.getBody().get("name").asText()).isEqualTo(payload.trim());
        var read = rest.getForEntity("/events/" + id, JsonNode.class);
        assertThat(read.getBody().get("name").asText()).isEqualTo(payload.trim());
        assertThat(jdbc.sql("SELECT name FROM events WHERE id = :id").param("id", UUID.fromString(id))
                .query(String.class).single()).isEqualTo(payload.trim());
        assertThat(count("SELECT COUNT(*) FROM events")).isGreaterThan((int) eventsBefore);
        assertThat(jdbc.sql("SELECT to_regclass('public.events') IS NOT NULL AND to_regclass('public.reservations') "
                + "IS NOT NULL AND to_regclass('public.idempotency_keys') IS NOT NULL "
                + "AND to_regclass('public.reservation_history') IS NOT NULL").query(Boolean.class).single()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM events WHERE available = 0 AND total_capacity = 7")).isZero();
    }

    @ParameterizedTest(name = "SEC-05 path [{0}]")
    @ValueSource(strings = { "1%27%20OR%20%271%27=%271", "%27%3B%20DROP%20TABLE%20events%3B--",
            "00000000-0000-0000-0000-000000000000%27%20OR%201=1--", "1;SELECT%20pg_sleep(5)" })
    @DisplayName("SEC-05 SQL injection no path (GET/DELETE/POST): 400 INVALID_ID_FORMAT, nada executado")
    void sqlInjectionInPathIsInvalidId(String payload) {
        long start = System.nanoTime();
        assertProblem(exchangeRawPath(HttpMethod.GET, "/events/" + payload, new HttpHeaders(), null),
                HttpStatus.BAD_REQUEST, "INVALID_ID_FORMAT");
        assertProblem(exchangeRawPath(HttpMethod.GET, "/reservations/" + payload, new HttpHeaders(), null),
                HttpStatus.BAD_REQUEST, "INVALID_ID_FORMAT");
        assertProblem(exchangeRawPath(HttpMethod.DELETE, "/reservations/" + payload, new HttpHeaders(), null),
                HttpStatus.BAD_REQUEST, "INVALID_ID_FORMAT");
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", newKey());
        assertProblem(exchangeRawPath(HttpMethod.POST, "/events/" + payload + "/reservations", headers,
                "{\"quantity\":1}"), HttpStatus.BAD_REQUEST, "INVALID_ID_FORMAT");
        assertThat((System.nanoTime() - start) / 1_000_000).as("pg_sleep nao foi executado (ms)").isLessThan(4_000);
    }

    @ParameterizedTest(name = "SEC-06 Idempotency-Key [{0}]")
    @ValueSource(strings = { "k'; DROP TABLE idempotency_keys;--", "' OR '1'='1", "k\\'; DELETE FROM reservations;--" })
    @DisplayName("SEC-06 SQL injection na Idempotency-Key: gravada literalmente, tabelas intactas")
    void sqlInjectionInKeyIsStoredAsText(String prefix) {
        UUID eventId = newEvent(5);
        String key = prefix + "-" + UUID.randomUUID();

        var res = postReservation(eventId, key, 1);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(keyRows(key)).isEqualTo(1);
        assertThat(postReservation(eventId, key, 1).getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(jdbc.sql("SELECT to_regclass('public.idempotency_keys') IS NOT NULL").query(Boolean.class)
                .single()).isTrue();
        assertThat(reservationRows(eventId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ SEC-07 / SEC-08: UUID

    @Test
    @DisplayName("SEC-07 UUID em maiusculas no path equivale ao minusculo; Location e ids saem em minusculas")
    void upperCaseUuidsAreEquivalent() {
        UUID eventId = newEvent(10);
        String upperEvent = eventId.toString().toUpperCase();

        var lowerGet = rest.getForEntity("/events/" + eventId, JsonNode.class);
        var upperGet = rest.getForEntity("/events/" + upperEvent, JsonNode.class);
        assertThat(upperGet.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(upperGet.getBody()).isEqualTo(lowerGet.getBody());
        assertThat(upperGet.getBody().get("id").asText()).isEqualTo(eventId.toString());

        var created = postReservationTo("/events/" + upperEvent + "/reservations", newKey(), "{\"quantity\":1}");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = created.getBody().get("id").asText();
        assertThat(created.getHeaders().getLocation().toString()).isEqualTo("/reservations/" + id)
                .isEqualTo(("/reservations/" + id).toLowerCase());
        assertThat(created.getBody().get("eventId").asText()).isEqualTo(eventId.toString());

        assertThat(rest.getForEntity("/reservations/" + id.toUpperCase(), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(HttpMethod.DELETE, "/reservations/" + id.toUpperCase(), null, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(physicalStatus(UUID.fromString(id))).isEqualTo("CANCELLED");
        assertThat(available(eventId)).isEqualTo(10);
    }

    @ParameterizedTest(name = "SEC-08 id [{0}] => {1}")
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "1-1-1-1-1|404|EVENT_NOT_FOUND",
            "00000000000000000000000000000000|400|INVALID_ID_FORMAT",
            "%7B00000000-0000-4000-8000-000000000001%7D|400|INVALID_ID_FORMAT",
            "urn:uuid:00000000-0000-4000-8000-000000000001|400|INVALID_ID_FORMAT",
            "0000000-0000-4000-8000-000000000001|404|EVENT_NOT_FOUND",
            "00000000-0000-4000-8000-00000000000g|400|INVALID_ID_FORMAT",
            "not-a-uuid|400|INVALID_ID_FORMAT",
            "00000000-0000-4000-8000-0000000000011|400|INVALID_ID_FORMAT" })
    @DisplayName("SEC-08 formatos exoticos de UUID: comportamento real do UUID.fromString fixado (D20)")
    void exoticUuidFormatsBehaveAsUuidFromString(String id, int status, String code) {
        var res = exchangeRawPath(HttpMethod.GET, "/events/" + id, new HttpHeaders(), null);

        assertProblem(res, HttpStatus.valueOf(status), code);
    }

    // ------------------------------------------------------------------ SEC-10 / SEC-13

    @Test
    @DisplayName("SEC-10 HTML/script no name: gravado e devolvido literalmente como application/json (sem XSS refletido)")
    void htmlInNameIsReturnedAsJsonText() {
        String payload = "<script>alert(1)</script><img src=x onerror=alert(2)>";

        var res = postEvent("{\"name\":\"" + payload + "\",\"capacity\":2}");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getHeaders().getContentType().toString()).startsWith("application/json");
        assertThat(res.getBody().get("name").asText()).isEqualTo(payload);
        var read = rest.getForEntity("/events/" + res.getBody().get("id").asText(), String.class);
        assertThat(read.getHeaders().getContentType().toString()).startsWith("application/json");
        assertThat(read.getBody()).contains("<script>alert(1)</script>");
    }

    @ParameterizedTest(name = "SEC-13 path [{0}]")
    @ValueSource(strings = { "/reservations/..%2f..%2fetc%2fpasswd", "/reservations/%2e%2e/%2e%2e/etc/passwd",
            "/events/..%5c..%5cwindows%5cwin.ini", "/reservations/%00", "/events/../../etc/passwd" })
    @DisplayName("SEC-13 path traversal em ids: 4xx, nada fora do contrato e a API segue saudavel")
    void pathTraversalIsRejected(String path) {
        var res = exchangeRawPathText(HttpMethod.GET, path);

        assertThat(res.getStatusCode().is4xxClientError()).as("status %s", res.getStatusCode()).isTrue();
        String text = res.getBody() == null ? "" : res.getBody();
        assertThat(text).doesNotContain("root:", "[fonts]");
        assertStillHealthy();
    }
}
