# Flash Booking - Catálogo de Cenários de Teste

Documento de QA do projeto Flash Booking (`C:\teste igor`). Base de referência: `Case BackEnd 1.pdf` (requisitos), `PLANO.md` (§4 contrato, §5 fluxos, §8 testes, §13 DoD), `README.md` (estado real) e leitura integral do código (`src/main`), da infraestrutura (`docker-compose.yml`, `docker/nginx/nginx.conf`, `Dockerfile`, `k6/booking.js`, `requests.http`) e de TODOS os testes (`src/test/java`).

Este documento é apenas análise: nenhum código de produção nem teste foi alterado. Os comportamentos marcados "(verificar)" são deduzidos do código e de padrões conhecidos do Spring/Jackson/PostgreSQL, mas nenhum teste existente os confirma; devem ser confirmados antes de virarem decisão.

## 1. Como usar este documento

**Tipo do cenário**

| Tipo | Significado |
|---|---|
| AUTO | Teste automatizado JUnit 5 + Testcontainers (PostgreSQL 16 real), executado por `./mvnw verify` |
| K6 | Carga/concorrência via nginx com as duas instâncias do Compose (`docker compose --profile load run --rm k6 run /scripts/booking.js`); NÃO roda no `mvn verify` |
| MANUAL | Roteiro executado na stack do Compose (seção 3); não faz sentido automatizar ou depende de derrubar containers |

**Situação da cobertura atual** (critério rigoroso: só é COBERTO se um teste existente de fato asserta o comportamento)

| Status | Significado |
|---|---|
| COBERTO | Existe teste (ou threshold k6) que asserta o comportamento descrito |
| PARCIAL | Existe teste que toca o cenário, mas não asserta tudo (por exemplo, checa só o status e não o `code`, ou só uma das variantes) |
| LACUNA | Nenhum teste existente asserta o cenário |

**Prioridade**: P0 = requisito central do PDF ou item do DoD (falha aqui reprova a entrega); P1 = risco real de regressão ou de comportamento indefinido; P2 = refinamento, robustez ou documentação.

**Notação**: `Classe#método` refere-se a `src/test/java/com/flashbooking/...`. Um cenário "COBERTO: X" significa que X contém a asserção. Classes de teste sugeridas com "(novo)" ainda não existem. "D1..D21" remetem à seção 6 (decisões e possíveis problemas). As colunas "Passos resumidos" e "Resultado esperado" descrevem o comportamento correto segundo PLANO.md/README e, quando o código diverge ou é ambíguo, o comportamento real deduzido do código.

**Suítes existentes (inventário)**: `EventApiTest` (15), `EventCacheTest` (1), `ReservationApiTest` (17), `ReservationCancelTest` (9), `ReservationConcurrencyTest` (5), `ReservationExpirationTest` (4), `ReservationExpirationConcurrencyTest` (6), `ReservationExpirationJobTest` (1), `MultiInstanceConcurrencyTest` (4), `DatabaseBusyTest` (3), `DatabaseStatementTimeoutTest` (1), `DatabasePoolExhaustedTest` (1), `DatabaseBusyMappingTest` (3), `GlobalExceptionHandlerTest` (7), `ConstraintsTest` (10). Helpers: `AbstractIntegrationTest`, `AbstractReservationTest`, `DbLock`, `StockInvariant`. Não há testes unitários puros de validação/hash (PLANO 8.1), nem teste de métricas, Actuator, Swagger, nginx, job com falha, reinício ou cache entre instâncias.

## 2. Catálogo de cenários

Cada tabela tem as colunas: ID, Cenário, Passos resumidos, Resultado esperado, Tipo, Cobertura atual, Prioridade.

**Resumo quantitativo:** 238 cenários catalogados: 119 COBERTO, 12 PARCIAL, 107 LACUNA.

| Área | Cenários | COBERTO | PARCIAL | LACUNA |
|---|---|---|---|---|
| Eventos (POST /events, GET /events/{id}, cache) | 30 | 13 | 4 | 13 |
| Reserva: validações | 26 | 12 | 2 | 12 |
| Estoque e oversell (concorrência) | 12 | 8 | 1 | 3 |
| Idempotência | 22 | 13 | 0 | 9 |
| Consulta de reserva (GET /reservations/{id}) | 11 | 7 | 1 | 3 |
| Cancelamento (DELETE /reservations/{id}) | 19 | 17 | 0 | 2 |
| Expiração | 19 | 10 | 1 | 8 |
| Histórico (reservation_history) | 12 | 9 | 0 | 3 |
| Erros e contrato HTTP | 28 | 17 | 2 | 9 |
| Resiliência e infraestrutura | 20 | 6 | 0 | 14 |
| Observabilidade | 15 | 1 | 0 | 14 |
| Multi-instância | 10 | 6 | 1 | 3 |
| Segurança e robustez básica (sem autenticação no escopo) | 14 | 0 | 0 | 14 |
| **Total** | **238** | **119** | **12** | **107** |

Por prioridade (COBERTO / PARCIAL / LACUNA): P0 84 / 1 / 2; P1 34 / 2 / 63; P2 1 / 9 / 42.

### 2.1 Eventos (POST /events, GET /events/{id}, cache)

| ID | Cenário | Passos resumidos | Resultado esperado | Tipo | Cobertura atual | Prioridade |
|---|---|---|---|---|---|---|
| EVT-01 | Criação válida | POST /events {"name":"Java Festival","capacity":50} | 201, Location /events/{id}, corpo com id, name, capacity=50, available=50, createdAt | AUTO | COBERTO: EventApiTest#createsEvent | P0 |
| EVT-02 | Limites de capacity aceitos (1 e 1.000.000) | POST com capacity=1 e capacity=1000000 | 201 nos dois | AUTO | COBERTO: EventApiTest#capacityBoundsAreAccepted | P1 |
| EVT-03 | capacity fora do intervalo (0, -1, 1.000.001) | POST com cada valor | 400 INVALID_CAPACITY + correlationId | AUTO | COBERTO: EventApiTest#capacityOutOfRangeIsRejected (parametrizado) | P0 |
| EVT-04 | capacity ausente ou null | POST sem o campo; POST com null | 400 INVALID_CAPACITY | AUTO | COBERTO: EventApiTest#missingCapacityIsRejected | P1 |
| EVT-05 | capacity decimal (50.5) | POST {"name":"A","capacity":50.5} | Contrato diz inteiro: o esperado seria 400. O código (Jackson com ACCEPT_FLOAT_AS_INT padrão) provavelmente aceita e trunca para 50 (201). Definir o comportamento e fixar em teste (seção 6, D2) | AUTO | LACUNA | P1 |
| EVT-06 | capacity como string numérica ("50") e não numérica ("abc") | POST com cada valor | "abc": 400 MALFORMED_REQUEST. "50": pelo Jackson padrão é coagido para 50 (201); decidir | AUTO | PARCIAL: EventApiTest#malformedJsonIsRejected cobre apenas "abc" | P2 |
| EVT-07 | capacity acima de int (3000000000) | POST com valor que estoura Integer | 400 (hoje MALFORMED_REQUEST, não INVALID_CAPACITY) | AUTO | LACUNA | P2 |
| EVT-08 | name com 1 caractere | POST name "A", capacity 10 | 201 | AUTO | LACUNA | P2 |
| EVT-09 | name com 150 caracteres | POST name com 150 "x" | 201 | AUTO | COBERTO: EventApiTest#nameWith150CharsIsAccepted | P1 |
| EVT-10 | name com 151 caracteres | POST name com 151 "x" | 400 INVALID_EVENT_NAME | AUTO | COBERTO: EventApiTest#tooLongNameIsRejected | P0 |
| EVT-11 | name só com espaços | POST name "   " | 400 INVALID_EVENT_NAME | AUTO | COBERTO: EventApiTest#blankNameIsRejected | P0 |
| EVT-12 | name vazio ("") | POST name "" | 400 INVALID_EVENT_NAME | AUTO | LACUNA | P2 |
| EVT-13 | name ausente ou null | POST sem name; POST name null | 400 INVALID_EVENT_NAME | AUTO | COBERTO: EventApiTest#missingNameIsRejected, EventApiTest#nullNameIsRejected | P1 |
| EVT-14 | name com espaços nas bordas (trim) e limite de 150 | POST name "  abc  "; POST name com 150 úteis + espaços nas bordas | "  abc  " vira "abc". O serviço aplica trim DEPOIS do @Size: 150 úteis + bordas (>150 brutos) são rejeitados (400). Decidir se é aceitável (D5) | AUTO | LACUNA | P2 |
| EVT-15 | name com unicode/acentos/emoji | POST name com acentos e emoji | 201; GET devolve exatamente a mesma string (UTF-8 fim a fim) | AUTO | LACUNA | P1 |
| EVT-16 | name no limite em emoji (unidades UTF-16 vs pontos de código) | POST name com 75, 76 e 150 emojis | @Size conta unidades UTF-16: 75 emojis passam e 76 falham, embora o VARCHAR(150) comporte 150 pontos de código. Decidir (D5) | AUTO | LACUNA | P2 |
| EVT-17 | name com caractere NUL (\u0000) | POST {"name":"a\u0000b","capacity":1} | Esperado 400 INVALID_EVENT_NAME. O PostgreSQL rejeita NUL em texto e o código não trata; desfecho provável: 500 INTERNAL_ERROR (verificar) | AUTO | LACUNA | P1 |
| EVT-18 | Corpo vazio | POST /events sem body, Content-Type JSON | 400 MALFORMED_REQUEST | AUTO | LACUNA | P1 |
| EVT-19 | JSON malformado | POST {"name": | 400 MALFORMED_REQUEST | AUTO | COBERTO: EventApiTest#malformedJsonIsRejected | P0 |
| EVT-20 | Content-Type errado (text/plain) ou ausente | POST /events com text/plain e corpo JSON | 415 em ProblemDetail. O @ExceptionHandler(Exception) captura HttpMediaTypeNotSupportedException e provavelmente devolve 500 INTERNAL_ERROR (verificar; D1) | AUTO | LACUNA | P1 |
| EVT-21 | Campos extras / tentativa de mass assignment | POST {"name":"A","capacity":5,"id":"...","available":999,"createdAt":"2000-01-01T00:00:00Z"} | 201; campos extras ignorados: id gerado pelo banco, available=capacity=5, createdAt do banco | AUTO | LACUNA | P1 |
| EVT-22 | Eventos com o mesmo nome | POST duas vezes o mesmo name | 201 nos dois, ids distintos (não há UNIQUE em name) | AUTO | LACUNA | P2 |
| EVT-23 | Criação concorrente de eventos | 50 POST /events em paralelo | 50 x 201 com 50 ids distintos | AUTO | LACUNA | P2 |
| EVT-24 | createdAt em ISO-8601 UTC | POST /events e ler createdAt | Formato Instant (termina em Z), próximo de now() | AUTO | PARCIAL: EventApiTest#createsEvent só asserta createdAt não vazio | P2 |
| EVT-25 | GET de evento inexistente | GET /events/{uuid aleatório} | 404 EVENT_NOT_FOUND | AUTO | COBERTO: EventApiTest#unknownEventReturns404 | P0 |
| EVT-26 | GET com UUID inválido | GET /events/not-a-uuid | 400 INVALID_ID_FORMAT | AUTO | COBERTO: EventApiTest#malformedUuidReturns400 | P0 |
| EVT-27 | GET do evento recém-criado | POST e GET | 200 com mesmos id/name/capacity/available | AUTO | COBERTO: EventApiTest#getsCreatedEvent | P0 |
| EVT-28 | Cache: valor antigo dentro do TTL e convergência depois | GET (popula), UPDATE direto no banco, GET imediato, GET após o TTL | Imediato: valor antigo; após o TTL: valor do banco | AUTO | COBERTO: EventCacheTest#servesCachedValueUntilTtlExpiresThenConverges (TTL 3s, uma instância, escrita por SQL) | P1 |
| EVT-29 | Cache real: GET após reserva pela mesma instância | GET (popula), POST reserva, GET imediato, GET após o TTL | Imediato: available antigo (não há evict); após 1s converge. Comportamento por desenho | AUTO | PARCIAL: EventCacheTest simula a escrita por SQL, não por POST /reservations | P2 |
| EVT-30 | Cache desabilitado lê direto do banco | Com cache.enabled=false: UPDATE direto e GET imediato | Valor novo imediatamente | AUTO | PARCIAL: nenhum teste asserta isso explicitamente (os demais testes apenas dependem do comportamento) | P2 |

### 2.2 Reserva: validações

| ID | Cenário | Passos resumidos | Resultado esperado | Tipo | Cobertura atual | Prioridade |
|---|---|---|---|---|---|---|
| RSV-01 | quantity mínima (1) | POST quantity 1 | 201 | AUTO | COBERTO: ReservationConcurrencyTest#singleTicketDisputedByOneHundredSellsExactlyOne (assert 201 x1) | P0 |
| RSV-02 | quantity no máximo (10) | POST quantity 10 em evento de 50 | 201 | AUTO | COBERTO: ReservationApiTest#maxQuantityIsAccepted | P1 |
| RSV-03 | quantity max+1 (11) | POST quantity 11 | 400 INVALID_QUANTITY; estoque intacto | AUTO | COBERTO: ReservationApiTest#invalidQuantityReturns400 | P0 |
| RSV-04 | quantity 0, -1, ausente ({}), null | POST com cada corpo | 400 INVALID_QUANTITY; estoque intacto | AUTO | COBERTO: ReservationApiTest#invalidQuantityReturns400 (parametrizado) | P0 |
| RSV-05 | quantity decimal (2.5) | POST {"quantity":2.5} | O esperado seria 400. Jackson padrão trunca para 2 e o hash da idempotência usa o valor coagido: provável 201 com quantity 2 (verificar; D2) | AUTO | LACUNA | P1 |
| RSV-06 | quantity string não numérica ("abc"), booleano, array | POST {"quantity":"abc"}, {"quantity":true}, {"quantity":[]} | 400 MALFORMED_REQUEST | AUTO | LACUNA | P1 |
| RSV-07 | quantity string numérica ("2") e acima de int (99999999999) | POST com cada valor | "2": coagido para 2 (201) pelo Jackson padrão, decidir. 99999999999: 400 (hoje MALFORMED_REQUEST) | AUTO | LACUNA | P2 |
| RSV-08 | Corpo vazio ou literal null | POST sem body; POST com corpo null | 400 MALFORMED_REQUEST | AUTO | LACUNA | P1 |
| RSV-09 | JSON malformado | POST {"quantity": | 400 MALFORMED_REQUEST | AUTO | COBERTO: ReservationApiTest#malformedJsonReturns400 | P0 |
| RSV-10 | Content-Type errado | POST reserva com text/plain | 415 em ProblemDetail (hoje provável 500; D1) | AUTO | LACUNA | P1 |
| RSV-11 | Campos extras no corpo | POST {"quantity":2,"foo":"bar","eventId":"outro"} | 201 ignorando extras; eventId do path prevalece | AUTO | LACUNA | P2 |
| RSV-12 | Idempotency-Key ausente | POST sem o header | 400 MISSING_IDEMPOTENCY_KEY | AUTO | COBERTO: ReservationApiTest#missingIdempotencyKeyReturns400 | P0 |
| RSV-13 | Idempotency-Key vazia e só espaço | POST com "" e com " " | 400 INVALID_IDEMPOTENCY_KEY | AUTO | COBERTO: ReservationApiTest#blankIdempotencyKeyReturns400 (parametrizado) | P0 |
| RSV-14 | Idempotency-Key com 1 caractere | POST com "a" | 201 | AUTO | LACUNA | P2 |
| RSV-15 | Idempotency-Key com 150 e 151 caracteres | POST com 151 e com 150 | 151: 400 INVALID_IDEMPOTENCY_KEY; 150: 201 | AUTO | COBERTO: ReservationApiTest#tooLongIdempotencyKeyReturns400 | P0 |
| RSV-16 | Idempotency-Key com caracteres especiais ASCII (: / # % " ' ; espaço interno) | POST com uma chave contendo esses caracteres | 201; chave tratada como texto opaco; replay com a mesma chave funciona | AUTO | LACUNA | P1 |
| RSV-17 | Idempotency-Key com unicode (bytes UTF-8 no header) | POST com chave contendo acentos | O Tomcat decodifica header como ISO-8859-1: a chave vira mojibake porém estável; chave com mais de 75 acentos (>150 bytes) cai em 400. Verificar e documentar (D15) | MANUAL | LACUNA | P2 |
| RSV-18 | Idempotency-Key com espaços nas bordas ("  abc  ") | POST com "  abc  " e depois "abc" | Servidores normalmente removem espaços das bordas do valor do header: seriam a mesma chave (replay). Verificar | AUTO | LACUNA | P2 |
| RSV-19 | Evento inexistente | POST reserva em UUID aleatório | 404 EVENT_NOT_FOUND, sem resquício (chave não retida, sem reserva/histórico) | AUTO | COBERTO: ReservationApiTest#unknownEventReturns404AndLeavesNoResidue | P0 |
| RSV-20 | UUID do evento malformado no path | POST /events/not-a-uuid/reservations | 400 INVALID_ID_FORMAT | AUTO | COBERTO: ReservationApiTest#malformedEventIdReturns400 | P0 |
| RSV-21 | Reserva que esgota exatamente (quantity == available) | Evento cap 2, POST quantity 2 | 201; available 0 | AUTO | COBERTO: ReservationCancelTest#cancellingFreesStockForNewReservation (reserva 2 em cap 2, available 0 ao fim) | P0 |
| RSV-22 | quantity maior que o disponível (ainda há estoque) | Evento cap 5 com 2 vendidos, POST quantity 4 | 409 INSUFFICIENT_CAPACITY; estoque e reservas intactos | AUTO | PARCIAL: ReservationExpirationTest#historyHasExactlyOneRowPerEffectiveTransition asserta 409 (quantity 5 > disponível 3) sem checar o code; ReservationApiTest#insufficientCapacityDoesNotRetainKey cobre o code, mas com disponível 0 | P0 |
| RSV-23 | quantity maior que a capacidade total | Evento cap 3, POST quantity 5 (max=10) | 409 INSUFFICIENT_CAPACITY (não 400); nenhuma chave retida | AUTO | PARCIAL: mesmo teste do RSV-22 (status 409, sem code) | P1 |
| RSV-24 | Precedência das validações | (a) header inválido + quantity inválida; (b) quantity inválida em evento inexistente; (c) path inválido + header ausente | (a) INVALID_IDEMPOTENCY_KEY; (b) 400 INVALID_QUANTITY (validação antes de consultar o evento); (c) INVALID_ID_FORMAT. Hoje apenas deduzido do código (D11) | AUTO | LACUNA | P2 |
| RSV-25 | Timestamps coerentes na criação (expiresAt = createdAt + TTL) | POST e comparar expiresAt - createdAt | Diferença = booking.reservation.ttl (10 min), relógio do banco | AUTO | LACUNA | P1 |
| RSV-26 | Resposta de criação completa | POST reserva e inspecionar | 201, Location /reservations/{id}, id/eventId/quantity/status PENDING/expiresAt/createdAt | AUTO | COBERTO: ReservationApiTest#createsReservationAndDecrementsStockWithHistory | P0 |

### 2.3 Estoque e oversell (concorrência)

| ID | Cenário | Passos resumidos | Resultado esperado | Tipo | Cobertura atual | Prioridade |
|---|---|---|---|---|---|---|
| STK-01 | 200 requisições simultâneas, capacity 50, quantity 1, chaves distintas | 200 POST em paralelo | 50 x 201, 150 x 409, available 0, 50 PENDING, 50 CREATED no histórico | AUTO | COBERTO: ReservationConcurrencyTest#twoHundredParallelRequestsOnFiftyTicketsNeverOversell | P0 |
| STK-02 | Quantities mistas (1..5) sobre capacity 60 | 150 POST em paralelo com quantities 1..5 | soma(201) <= 60, available = 60 - vendido, invariante | AUTO | COBERTO: ReservationConcurrencyTest#mixedQuantitiesNeverExceedCapacity | P0 |
| STK-03 | Capacity 1 disputada por 100 | 100 POST em paralelo | 1 x 201, 99 x 409 | AUTO | COBERTO: ReservationConcurrencyTest#singleTicketDisputedByOneHundredSellsExactlyOne | P0 |
| STK-04 | Vários eventos disputados ao mesmo tempo | 300 POST distribuídos em 5 eventos (cap 5, 20, 33, 1, 50) | Cada evento vende exatamente sua capacity; invariante por evento | AUTO | COBERTO: ReservationConcurrencyTest#severalEventsDisputedAtTheSameTimeKeepEachInvariant | P0 |
| STK-05 | Capacity ímpar com quantity 2 | 100 POST quantity 2 em cap 51 | 25 x 201, available 1 | AUTO | COBERTO: ReservationConcurrencyTest#quantityTwoOnOddCapacityLeavesOneTicketAndSellsTwentyFive | P0 |
| STK-06 | Quantities mistas que fecham EXATAMENTE a capacity | cap 10; tentativas com quantities 3 e 2 em paralelo, com sobra | Sem oversell e sem 5xx; quando a combinação fecha, available chega a 0 | AUTO | LACUNA | P2 |
| STK-07 | Invariante total_capacity = available + soma(PENDING) em todo teste de escrita | StockInvariant.assertHolds ao fim de cada teste | Sempre verdadeira | AUTO | COBERTO: StockInvariant usado em ReservationApiTest, ReservationCancelTest, ReservationConcurrencyTest, ReservationExpiration*Test, MultiInstanceConcurrencyTest, Database*Test | P0 |
| STK-08 | CHECK impede available negativo/acima da capacidade e capacity <= 0 | UPDATE/INSERT direto violando | DataIntegrityViolationException | AUTO | COBERTO: ConstraintsTest#availableCannotBeNegative, #availableCannotExceedTotalCapacity, #eventCapacityMustBePositive | P0 |
| STK-09 | Reservar e cancelar concorrentes no mesmo evento esgotado | Evento cap N esgotado; N DELETE + M POST simultâneos | Nenhum 5xx; invariante; total de PENDING <= cap | AUTO | LACUNA | P1 |
| STK-10 | Disputa real via nginx com 2 instâncias (200 VUs, 50 ingressos) | docker compose --profile load run --rm k6 run /scripts/booking.js | 50 x 201, 150 x 409, available 0 (thresholds do script) | K6 | COBERTO: k6/booking.js#stampede + teardown (execução manual; não roda no mvn verify) | P0 |
| STK-11 | Hot row sob lock_timeout padrão (1s) e 200 concorrentes | k6 stampede contra o Compose (config padrão, sem perfil test) | Possíveis 503 tratados por retry com a mesma chave; zero oversell no fim | K6 | PARCIAL: k6/booking.js#stampede refaz 503 com a mesma chave, mas nenhum threshold limita a taxa de 503 | P1 |
| STK-12 | Capacity 1.000.000 com várias reservas | Evento cap 1.000.000, 100 reservas de 10 | available = 999.000 (sem overflow de INTEGER) | AUTO | LACUNA | P2 |

### 2.4 Idempotência

| ID | Cenário | Passos resumidos | Resultado esperado | Tipo | Cobertura atual | Prioridade |
|---|---|---|---|---|---|---|
| IDP-01 | Replay sequencial: mesma chave e mesmo request | POST duas vezes com a mesma chave | 2ª resposta: 201, mesmo corpo, mesmo Location, Idempotent-Replayed: true; 1 reserva, estoque -1, 1 linha de histórico | AUTO | COBERTO: ReservationApiTest#replayReturnsSameResponseWithHeaderAndDoesNotWriteAgain | P0 |
| IDP-02 | Replay concorrente (20 requisições, mesma chave) | 20 POST em paralelo | 20 x 201 iguais, 19 com Idempotent-Replayed, 1 reserva, 1 histórico, 1 chave, estoque -2 uma vez | AUTO | COBERTO: ReservationApiTest#concurrentRequestsWithSameKeyCreateOneReservation | P0 |
| IDP-03 | Mesma chave com quantity diferente | POST q=2 e depois q=3 | 409 IDEMPOTENCY_KEY_CONFLICT; estoque inalterado | AUTO | COBERTO: ReservationApiTest#sameKeyWithDifferentQuantityConflicts | P0 |
| IDP-04 | Mesma chave em outro evento | POST evento A e depois evento B | 409 IDEMPOTENCY_KEY_CONFLICT; B intacto | AUTO | COBERTO: ReservationApiTest#sameKeyOnDifferentEventConflicts | P0 |
| IDP-05 | Chave não retida após 409 por falta de estoque | POST 409; devolver estoque; repetir a mesma chave | 1ª: 409 sem linha em idempotency_keys; retry: 201 sem Idempotent-Replayed | AUTO | COBERTO: ReservationApiTest#insufficientCapacityDoesNotRetainKey | P0 |
| IDP-06 | Chave não retida após 404 (evento inexistente) | POST em evento inexistente | Nenhuma linha em idempotency_keys | AUTO | COBERTO: ReservationApiTest#unknownEventReturns404AndLeavesNoResidue | P1 |
| IDP-07 | Chave não retida após 503 (lock timeout) | Travar a linha do evento; POST; liberar; repetir a chave | 503 sem resquício; retry 201 | AUTO | COBERTO: DatabaseBusyTest#lockTimeoutOnPostReturns503LeavesNothingBehindAndRetryWithSameKeySucceeds | P0 |
| IDP-08 | Chave contendida por transação aberta vira 503 e funciona depois | Transação externa insere a chave; POST com a mesma chave | 503; após liberar, 201 | AUTO | COBERTO: DatabaseBusyTest#contendedIdempotencyKeyTimesOutAs503AndWorksAfterRelease | P1 |
| IDP-09 | Replay APÓS o cancelamento da reserva original | POST (201); DELETE da reserva; POST com a mesma chave e corpo | Comportamento real (ReservationService.java:135-143): 201 com o corpo ORIGINAL (status PENDING, congelado), Idempotent-Replayed: true, sem nova reserva, sem baixa/devolução de estoque. Decidir se é o desejado (D3) | AUTO | LACUNA | P1 |
| IDP-10 | Replay APÓS a expiração da reserva original | POST; vencer a reserva; expireAll; POST com a mesma chave | 201 com corpo original (PENDING), replayed, sem nova reserva e sem nova baixa | AUTO | LACUNA | P1 |
| IDP-11 | Canonicalização do corpo (espaços, ordem, campos extras, 2.0 vs 2) | POST {"quantity":2} e depois {  "quantity" : 2 , "x":1 } com a mesma chave | Mesmo hash (o hash usa o valor validado): 2ª chamada é replay, não conflito | AUTO | LACUNA | P1 |
| IDP-12 | UUID do evento em maiúsculas gera o mesmo hash | POST em /events/{uuid minúsculo} e depois /events/{UUID MAIÚSCULO} com a mesma chave | Replay (hash usa o UUID em minúsculas), não conflito | AUTO | LACUNA | P1 |
| IDP-13 | Chave diferencia maiúsculas de minúsculas ("Abc" != "abc") | POST com "Abc" e "abc" | Duas reservas distintas | AUTO | LACUNA | P2 |
| IDP-14 | Replay concorrente quando a 1ª tentativa falha por falta de estoque | Evento esgotado; 20 POST simultâneos com a mesma chave | Todos 409 INSUFFICIENT_CAPACITY, nenhuma chave retida, nenhum 5xx | AUTO | LACUNA | P1 |
| IDP-15 | Replay concorrente com liberação de estoque no meio | Mesma chave em paralelo enquanto DELETE libera estoque | No máximo uma reserva resulta da chave; respostas 201 (original + replays) ou 409; invariante | AUTO | LACUNA | P2 |
| IDP-16 | Replay entre instâncias | 20 POST com a mesma chave alternando api1/api2 | 20 x 201 iguais, 19 replayed, 1 reserva | AUTO | COBERTO: MultiInstanceConcurrencyTest#idempotencyHoldsAcrossInstances | P0 |
| IDP-17 | Idempotência sob k6 (20 requisições paralelas via nginx) | k6 cenário same_key | Um único id, todas 201, no máximo 1 não-replay | K6 | COBERTO: k6/booking.js#sameKey | P1 |
| IDP-18 | Conflito não altera a chave original | POST q=2 (chave K); POST q=3 (K, 409); POST q=2 (K) | A 3ª chamada ainda é replay 201 da reserva original | AUTO | LACUNA | P2 |
| IDP-19 | Idempotent-Replayed ausente na 1ª resposta, true nos replays; Location igual | POST/POST | 1ª sem o header; 2ª com true e Location igual | AUTO | COBERTO: ReservationApiTest#createsReservationAndDecrementsStockWithHistory (isNull) e #replayReturnsSameResponseWithHeaderAndDoesNotWriteAgain | P1 |
| IDP-20 | Várias chaves diferentes no mesmo evento criam reservas independentes | N POST com chaves distintas | N reservas, estoque -N | AUTO | COBERTO: ReservationConcurrencyTest (chave distinta por requisição) | P1 |
| IDP-21 | Unicidade da chave no banco | INSERT duplicado; ON CONFLICT DO NOTHING | Duplicado viola a PK; ON CONFLICT devolve 0 linhas | AUTO | COBERTO: ConstraintsTest#idempotencyKeyIsUnique | P1 |
| IDP-22 | Crescimento sem limite da tabela idempotency_keys | Criar N reservas e inspecionar a tabela | Hoje as chaves nunca expiram (limitação declarada no README); não há rotina de limpeza nem teste | MANUAL | LACUNA | P2 |

### 2.5 Consulta de reserva (GET /reservations/{id})

| ID | Cenário | Passos resumidos | Resultado esperado | Tipo | Cobertura atual | Prioridade |
|---|---|---|---|---|---|---|
| QRY-01 | Consulta de reserva PENDING | POST e GET /reservations/{id} | 200, status PENDING, expiresAt igual ao do POST | AUTO | COBERTO: ReservationApiTest#createsReservationAndDecrementsStockWithHistory | P0 |
| QRY-02 | PENDING vencida antes do job: status efetivo EXPIRED sem efeito colateral | Vencer a reserva por SQL; GET | 200 status EXPIRED; estado físico segue PENDING; estoque não devolvido | AUTO | COBERTO: ReservationApiTest#getReportsExpiredForOverduePendingWithoutReturningStock; ReservationExpirationTest#expiresOnlyDueReservationsReturnsStockAndWritesHistory (I12) | P0 |
| QRY-03 | Consulta de reserva CANCELLED | DELETE e GET | 200 status CANCELLED | AUTO | COBERTO: ReservationCancelTest#cancelsPendingReturnsStockAndWritesHistory | P0 |
| QRY-04 | Consulta de reserva EXPIRED física (após o job) | expireBatch e GET | 200 status EXPIRED | AUTO | COBERTO: ReservationExpirationTest#expiresOnlyDueReservationsReturnsStockAndWritesHistory | P0 |
| QRY-05 | Reserva inexistente | GET /reservations/{uuid aleatório} | 404 RESERVATION_NOT_FOUND | AUTO | COBERTO: ReservationApiTest#getUnknownReservationReturns404 | P0 |
| QRY-06 | UUID inválido | GET /reservations/xyz | 400 INVALID_ID_FORMAT | AUTO | COBERTO: ReservationApiTest#getReservationWithMalformedIdReturns400 | P0 |
| QRY-07 | Timestamps coerentes: expiresAt = createdAt + TTL, createdAt <= expiresAt | GET de reserva recém-criada | expiresAt - createdAt = 10 min; ambos em UTC (sufixo Z) | AUTO | LACUNA | P1 |
| QRY-08 | Fronteira exata do vencimento (expires_at == agora) | Reserva com expires_at = NOW(); GET | Usa <= : status EXPIRED (PLANO 4) | AUTO | LACUNA | P2 |
| QRY-09 | GET lê direto do banco (sem cache) | DELETE e GET imediato, repetidos | Sempre o estado atual | AUTO | PARCIAL: ReservationCancelTest#cancelsPending... faz DELETE seguido de GET, mas o serviço de reserva não tem cache e nenhum teste o afirma de forma explícita | P2 |
| QRY-10 | GET não tem efeito colateral em reserva vencida | GET repetido em reserva vencida | Nenhuma linha de histórico nova; status físico PENDING | AUTO | COBERTO: ReservationApiTest#getReportsExpiredForOverduePendingWithoutReturningStock (estado físico e estoque) | P1 |
| QRY-11 | Leitura de reserva criada em outra instância | POST em api1, GET em api2 | 200 com o mesmo corpo | AUTO | LACUNA | P1 |

### 2.6 Cancelamento (DELETE /reservations/{id})

| ID | Cenário | Passos resumidos | Resultado esperado | Tipo | Cobertura atual | Prioridade |
|---|---|---|---|---|---|---|
| CAN-01 | Cancelamento feliz | POST; DELETE | 204 sem corpo; status físico CANCELLED; estoque devolvido; histórico CANCELLED com correlation_id | AUTO | COBERTO: ReservationCancelTest#cancelsPendingReturnsStockAndWritesHistory | P0 |
| CAN-02 | DELETE repetido | DELETE duas vezes | 204 nas duas; estoque devolvido 1 vez; 1 linha CANCELLED | AUTO | COBERTO: ReservationCancelTest#repeatedDeleteReturns204ButReturnsStockOnlyOnce | P0 |
| CAN-03 | DELETE concorrente da mesma reserva (20) | 20 DELETE em paralelo | 20 x 204; estoque devolvido 1 vez; 1 linha CANCELLED | AUTO | COBERTO: ReservationCancelTest#twentyConcurrentDeletesOfSameReservationReturnStockOnce | P0 |
| CAN-04 | PENDING já vencida (job ainda não rodou) | Vencer por SQL; DELETE | 409 INVALID_RESERVATION_STATE; nada muda | AUTO | COBERTO: ReservationCancelTest#deleteOfPendingAlreadyPastExpiryIs409AndChangesNothing | P0 |
| CAN-05 | Reserva EXPIRED | Marcar EXPIRED; DELETE | 409 INVALID_RESERVATION_STATE | AUTO | COBERTO: ReservationCancelTest#deleteOfExpiredReservationIs409 | P0 |
| CAN-06 | Reserva inexistente | DELETE /reservations/{uuid aleatório} | 404 RESERVATION_NOT_FOUND | AUTO | COBERTO: ReservationCancelTest#deleteOfUnknownReservationIs404 | P0 |
| CAN-07 | UUID inválido | DELETE /reservations/not-a-uuid | 400 INVALID_ID_FORMAT | AUTO | COBERTO: ReservationCancelTest#deleteWithMalformedIdIs400 | P0 |
| CAN-08 | Devolução libera nova reserva | Esgotar evento; DELETE; POST | 1ª tentativa 409; após DELETE, 201; available 0 | AUTO | COBERTO: ReservationCancelTest#cancellingFreesStockForNewReservation | P0 |
| CAN-09 | Cancelar e reservar concorrentes (evento esgotado) | N DELETE + M POST simultâneos | Sem 5xx; invariante; PENDING <= cap | AUTO | LACUNA | P1 |
| CAN-10 | Cancelar reserva de evento esgotado (available 0 -> devolução) | Evento 100% vendido; DELETE das reservas | available sobe ao valor correto, invariante | AUTO | COBERTO: ReservationCancelTest#thirtyParallelCancellationsOfDifferentReservationsKeepInvariant | P1 |
| CAN-11 | Cancelar x expirar concorrentes (reserva vencida) | 10 reservas vencidas; 2 jobs + 10 DELETE em paralelo | DELETE sempre 409; só o job devolve; 10 EXPIRED; 0 CANCELLED | AUTO | COBERTO: ReservationExpirationConcurrencyTest#i9_deleteOfDueReservationRacingWithExpirationReturnsStockOnce | P0 |
| CAN-12 | Cancelar válidas enquanto o job expira outras do mesmo evento | 10 válidas + 30 vencidas; 2 jobs + 10 DELETE | 10 x 204; 10 CANCELLED, 30 EXPIRED; available = cap | AUTO | COBERTO: ReservationExpirationConcurrencyTest#i9_cancellingValidReservationsWhileJobExpiresOthersOfSameEvent | P0 |
| CAN-13 | Cancelar na fronteira exata do vencimento | 15 reservas com expires_at em +400 ms; DELETE + job colados à fronteira | Cada reserva tem exatamente uma transição final; estoque devolvido uma vez | AUTO | COBERTO: ReservationExpirationConcurrencyTest#i9_cancelRacingExactlyAtTheExpiryBoundaryReturnsStockExactlyOnce | P1 |
| CAN-14 | Cancelamento sob lock timeout | Travar a reserva; DELETE | 503 DATABASE_BUSY + Retry-After; nada muda; DELETE seguinte funciona e devolve 1 vez | AUTO | COBERTO: DatabaseBusyTest#lockTimeoutOnDeleteReturns503ThenDeleteSucceedsAndReturnsStockOnce; DatabaseStatementTimeoutTest#statementTimeoutReturns503AndLeavesNothingBehind | P0 |
| CAN-15 | Cancelamentos paralelos de reservas diferentes do mesmo evento | 30 DELETE em paralelo | 30 x 204; 30 linhas CANCELLED; available = 30 | AUTO | COBERTO: ReservationCancelTest#thirtyParallelCancellationsOfDifferentReservationsKeepInvariant | P1 |
| CAN-16 | Cancelamento entre instâncias | DELETE alternado em api1/api2 enquanto os jobs rodam | 60 x 204; 60 CANCELLED; nenhuma EXPIRED | AUTO | COBERTO: MultiInstanceConcurrencyTest#expirationJobsOfBothInstancesExpireEachReservationOnceWhileCancellationsRun | P1 |
| CAN-17 | DELETE em reserva CANCELLED cuja PENDING venceria | Cancelar; esperar passar o expires_at; DELETE de novo; rodar o job | 204 no DELETE repetido; o job NÃO expira reserva CANCELLED; estoque inalterado | AUTO | LACUNA | P1 |
| CAN-18 | Cancelar devolve só a quantity da reserva (não a capacity) | Reservar 3 de 10; cancelar | available volta a 10 exatamente | AUTO | COBERTO: ReservationCancelTest#cancelsPendingReturnsStockAndWritesHistory | P1 |
| CAN-19 | DELETE devolve 204 sem corpo e sem Content-Type | DELETE | Corpo vazio | AUTO | COBERTO: ReservationCancelTest#cancelsPendingReturnsStockAndWritesHistory (body null) | P2 |

### 2.7 Expiração

| ID | Cenário | Passos resumidos | Resultado esperado | Tipo | Cobertura atual | Prioridade |
|---|---|---|---|---|---|---|
| EXP-01 | Lote expira só vencidas, devolve estoque e grava histórico | 3 reservas (2 vencidas, 1 válida); expireBatch | 2 EXPIRED, 1 PENDING; available devolvido; 1 linha EXPIRED por vencida com correlation_id job-xxxxxxxx | AUTO | COBERTO: ReservationExpirationTest#expiresOnlyDueReservationsReturnsStockAndWritesHistory | P0 |
| EXP-02 | Sem nada vencido: lote vazio | expireBatch sem vencidas | Retorna 0; nada muda | AUTO | COBERTO: ReservationExpirationTest#expireBatchWithNothingDueReturnsZeroAndChangesNothing | P1 |
| EXP-03 | Vários lotes e limite de iterações | 250 vencidas, batch 100; expireAll(1) e expireAll(1000) | expireAll(1) = 100; expireAll(1000) drena tudo | AUTO | COBERTO: ReservationExpirationTest#expireAllDrainsMultipleBatchesUpToIterationLimit | P1 |
| EXP-04 | Expiração concorrente (4 execuções) | 60 reservas vencidas; 4 expireAll em paralelo | Cada reserva expira 1 vez, estoque devolvido 1 vez, 60 históricos EXPIRED | AUTO | COBERTO: ReservationExpirationConcurrencyTest#i7_concurrentExpirationExpiresEachReservationExactlyOnce | P0 |
| EXP-05 | SKIP LOCKED divide o trabalho | 400 vencidas, batch 7, 4 execuções | Mais de um correlation_id de job; soma = 400 | AUTO | COBERTO: ReservationExpirationConcurrencyTest#i7_instancesShareTheWorkViaSkipLocked | P1 |
| EXP-06 | Vários eventos com lotes mistos (sem deadlock) | 4 eventos x 20 vencidas, 2 execuções, 20 rodadas | Sem exceção (40P01); estoque de cada evento correto | AUTO | COBERTO: ReservationExpirationConcurrencyTest#i8_mixedBatchesAcrossEventsNeverDeadlock | P0 |
| EXP-07 | Fronteira do vencimento | Ver CAN-13 | Uma transição final por reserva | AUTO | COBERTO: ReservationExpirationConcurrencyTest#i9_cancelRacingExactlyAtTheExpiryBoundaryReturnsStockExactlyOnce | P1 |
| EXP-08 | Job @Scheduled ligado expira sem chamada manual | Contexto com job ligado (200 ms); vencer reserva | Vira EXPIRED sozinha; estoque devolvido; histórico 1 | AUTO | COBERTO: ReservationExpirationJobTest#scheduledJobExpiresDueReservationsAndReturnsStockWithoutManualCall | P0 |
| EXP-09 | Job em 2 instâncias (JVMs de teste) com cancelamentos | 200 vencidas + 60 canceladas via HTTP | 200 EXPIRED 1x cada; estoque correto; 60 CANCELLED | AUTO | COBERTO: MultiInstanceConcurrencyTest#expirationJobsOfBothInstancesExpireEachReservationOnceWhileCancellationsRun | P0 |
| EXP-10 | Batch-size menor que a fila | batch 5 (MIT) e batch 7 (ECC) com centenas de vencidas | Todas expiradas em vários lotes | AUTO | COBERTO: MultiInstanceConcurrencyTest (batch 5) e ReservationExpirationConcurrencyTest (batch 7) | P1 |
| EXP-11 | Exceção no ciclo do job não derruba o scheduler | Fazer expireAll lançar (mock/DB indisponível) e ver o ciclo seguinte rodar | Job loga warn e o próximo ciclo funciona | AUTO | LACUNA | P1 |
| EXP-12 | Reinício da aplicação com vencidas pendentes | Semear vencidas com a app parada; subir a app | 1º ciclo após a subida drena todas | AUTO | LACUNA | P1 |
| EXP-13 | TTL configurável | Subir com booking.reservation.ttl=2s; POST; esperar | expiresAt = createdAt + 2s; após ~2s+delay a reserva é EXPIRED fisicamente e o estoque volta | AUTO | LACUNA | P1 |
| EXP-14 | Expiração ponta a ponta com TTL real (sem manipular o banco) | Roteiro manual com TTL curto via env | Reserva vira EXPIRED sozinha; estoque volta; DELETE 409 | MANUAL | LACUNA | P1 |
| EXP-15 | Job não toca reservas CANCELLED/EXPIRED | CANCELLED com expires_at vencido; expireAll | Permanece CANCELLED; sem linha EXPIRED; estoque inalterado | AUTO | LACUNA | P2 |
| EXP-16 | Estoque só volta após o job | Vencer reserva de evento esgotado; POST novo antes e depois do job | Antes do job: 409 (GET já diz EXPIRED); depois: 201 | AUTO | LACUNA | P2 |
| EXP-17 | Reserva travada por DELETE em andamento é ignorada (SKIP LOCKED) e expirada no ciclo seguinte | Segurar a linha com DbLock.lockReservation; expireBatch; liberar; expireBatch | 1º lote não a vê (0); 2º a expira; sem espera | AUTO | LACUNA | P2 |
| EXP-18 | Métrica reservations.expired incrementa pelo tamanho do lote após o commit | expireBatch com N vencidas | counter sobe N | AUTO | LACUNA | P1 |
| EXP-19 | Limite de 20 iterações por ciclo (2.000 por ciclo com batch 100) | Backlog de 2.500 vencidas e job com delay curto | Ciclo 1 expira <= 2.000; ciclos seguintes drenam o resto | AUTO | PARCIAL: ReservationExpirationTest#expireAllDrainsMultipleBatchesUpToIterationLimit valida o limite no service com valor explícito, não o MAX_ITERATIONS do job | P2 |

### 2.8 Histórico (reservation_history)

| ID | Cenário | Passos resumidos | Resultado esperado | Tipo | Cobertura atual | Prioridade |
|---|---|---|---|---|---|---|
| HST-01 | CREATED: campos do histórico | POST reserva | 1 linha: CREATED, previous NULL, new PENDING, quantity, CLIENT_REQUEST, correlation_id enviado, instance_id, event_id | AUTO | COBERTO: ReservationApiTest#createsReservationAndDecrementsStockWithHistory | P0 |
| HST-02 | CANCELLED: campos do histórico | DELETE | 1 linha: CANCELLED, PENDING -> CANCELLED, CLIENT_REQUEST, correlation_id do DELETE | AUTO | COBERTO: ReservationCancelTest#cancelsPendingReturnsStockAndWritesHistory | P0 |
| HST-03 | EXPIRED: campos do histórico | Job | 1 linha: EXPIRED, PENDING -> EXPIRED, TTL_EXPIRED, correlation_id job-xxxxxxxx, instance_id, quantity | AUTO | COBERTO: ReservationExpirationTest#expiresOnlyDueReservationsReturnsStockAndWritesHistory | P0 |
| HST-04 | Sem linhas em replay, 409 (estoque), DELETE repetido, DELETE 409 | Sequência completa | Exatamente 1 linha por transição efetiva (4 no cenário do teste) | AUTO | COBERTO: ReservationExpirationTest#historyHasExactlyOneRowPerEffectiveTransition; ReservationApiTest#replayReturns...; ReservationCancelTest#repeatedDelete..., #deleteOfPendingAlreadyPastExpiry... | P0 |
| HST-05 | Rollback remove o histórico junto (404, 503, 409 capacidade) | Forçar cada falha | Nenhuma linha de histórico, reserva ou chave | AUTO | COBERTO: ReservationApiTest#insufficientCapacityDoesNotRetainKey, #unknownEventReturns404AndLeavesNoResidue, DatabaseBusyTest#lockTimeoutOnPostReturns503... | P0 |
| HST-06 | correlation_id nunca nulo nas transições via API | Inspecionar o histórico | 0 linhas com correlation_id NULL | AUTO | COBERTO: ReservationExpirationTest#historyHasExactlyOneRowPerEffectiveTransition | P1 |
| HST-07 | instance_id reflete a instância que executou | POST em api1/api2 | Ambas aparecem no histórico do evento | AUTO | COBERTO: MultiInstanceConcurrencyTest#i3_twoHundredRequestsSplitAcrossTwoInstancesNeverOversell (DISTINCT instance_id = 2) | P1 |
| HST-08 | CHECKs e FK do histórico | Inserir action/status inválidos e reservation_id inexistente | DataIntegrityViolationException | AUTO | COBERTO: ConstraintsTest#historyConstraints | P1 |
| HST-09 | Histórico é append-only: o código nunca faz UPDATE/DELETE em reservation_history | Varrer SQL do código ou bloquear UPDATE/DELETE por trigger/REVOKE em teste | Nenhuma instrução UPDATE/DELETE alcança a tabela | AUTO | LACUNA | P2 |
| HST-10 | Uma reserva tem no máximo 2 linhas (CREATED + 1 final) | Ciclos completos com cancelar/expirar/repetir | Nunca CANCELLED e EXPIRED para a mesma reserva | AUTO | COBERTO: ReservationExpirationConcurrencyTest#i9_cancelRacingExactlyAtTheExpiryBoundaryReturnsStockExactlyOnce (cancelled+expired == 1) | P1 |
| HST-11 | X-Correlation-Id com 64 caracteres é aceito; com 65 é substituído | POST com cada tamanho e inspecionar histórico | 64: gravado igual; 65: novo UUID (nunca estoura VARCHAR(64)) | AUTO | LACUNA | P1 |
| HST-12 | Histórico do evento de job com instância correta no Compose | Expiração real no Compose; SELECT em reservation_history | correlation_id job-..., instance_id api1 ou api2 | MANUAL | LACUNA | P2 |

### 2.9 Erros e contrato HTTP

| ID | Cenário | Passos resumidos | Resultado esperado | Tipo | Cobertura atual | Prioridade |
|---|---|---|---|---|---|---|
| ERR-01 | MISSING_IDEMPOTENCY_KEY (400) | POST reserva sem header | 400, code, correlationId | AUTO | COBERTO: ReservationApiTest#missingIdempotencyKeyReturns400; GlobalExceptionHandlerTest#missingIdempotencyKeyHeader | P0 |
| ERR-02 | INVALID_IDEMPOTENCY_KEY (400) | Chave vazia/branca/151 chars | 400, code, correlationId | AUTO | COBERTO: ReservationApiTest#blankIdempotencyKeyReturns400, #tooLongIdempotencyKeyReturns400 | P0 |
| ERR-03 | INVALID_QUANTITY (400) | quantity fora de 1..max | 400, code, correlationId | AUTO | COBERTO: ReservationApiTest#invalidQuantityReturns400 | P0 |
| ERR-04 | INVALID_CAPACITY (400) | capacity fora de 1..1.000.000 ou ausente | 400, code, correlationId | AUTO | COBERTO: EventApiTest#capacityOutOfRangeIsRejected, #missingCapacityIsRejected | P0 |
| ERR-05 | INVALID_EVENT_NAME (400) | name vazio/ausente/>150 | 400, code, correlationId | AUTO | COBERTO: EventApiTest#blankNameIsRejected, #missingNameIsRejected, #nullNameIsRejected, #tooLongNameIsRejected | P0 |
| ERR-06 | INVALID_ID_FORMAT (400) | UUID malformado em GET/POST/DELETE | 400, code, correlationId | AUTO | COBERTO: EventApiTest#malformedUuidReturns400, ReservationApiTest#malformedEventIdReturns400, #getReservationWithMalformedIdReturns400, ReservationCancelTest#deleteWithMalformedIdIs400 | P0 |
| ERR-07 | MALFORMED_REQUEST (400) | JSON inválido / tipo errado | 400, code, correlationId | AUTO | COBERTO: EventApiTest#malformedJsonIsRejected, ReservationApiTest#malformedJsonReturns400, GlobalExceptionHandlerTest#malformedJsonIsMalformedRequest | P0 |
| ERR-08 | EVENT_NOT_FOUND (404) | GET/POST em evento inexistente | 404, code, correlationId | AUTO | COBERTO: EventApiTest#unknownEventReturns404, ReservationApiTest#unknownEventReturns404AndLeavesNoResidue | P0 |
| ERR-09 | RESERVATION_NOT_FOUND (404) | GET/DELETE em reserva inexistente | 404, code, correlationId | AUTO | COBERTO: ReservationApiTest#getUnknownReservationReturns404, ReservationCancelTest#deleteOfUnknownReservationIs404 | P0 |
| ERR-10 | INSUFFICIENT_CAPACITY (409) | POST sem estoque | 409, code, correlationId | AUTO | COBERTO: ReservationApiTest#insufficientCapacityDoesNotRetainKey | P0 |
| ERR-11 | INVALID_RESERVATION_STATE (409) | DELETE de EXPIRED/PENDING vencida | 409, code, correlationId | AUTO | COBERTO: ReservationCancelTest#deleteOfExpiredReservationIs409, #deleteOfPendingAlreadyPastExpiryIs409AndChangesNothing | P0 |
| ERR-12 | IDEMPOTENCY_KEY_CONFLICT (409) | Mesma chave, request diferente | 409, code, correlationId | AUTO | COBERTO: ReservationApiTest#sameKeyWithDifferentQuantityConflicts, #sameKeyOnDifferentEventConflicts | P0 |
| ERR-13 | DATABASE_BUSY (503) + Retry-After: 1 | Lock timeout, statement timeout, pool esgotado | 503, Retry-After 1, code, correlationId, sem SQL/stack no corpo | AUTO | COBERTO: DatabaseBusyTest, DatabaseStatementTimeoutTest#statementTimeoutReturns503AndLeavesNothingBehind, DatabasePoolExhaustedTest#exhaustedPoolReturns503ThenRecovers | P0 |
| ERR-14 | INTERNAL_ERROR (500) sem vazar detalhes | Exceção inesperada | 500, code INTERNAL_ERROR, corpo sem a mensagem interna nem stack | AUTO | COBERTO: GlobalExceptionHandlerTest#unexpectedErrorIs500WithoutLeakingDetails (controller de teste, não um caminho real da API) | P0 |
| ERR-15 | Deadlock (40P01) mapeado para 503 | Exceção encadeada com SQLState 40P01 | isDatabaseBusy = true; 55P03 e 57014 idem; 23505 não | AUTO | COBERTO: DatabaseBusyMappingTest (somente unitário; deadlock real não reproduzido) | P1 |
| ERR-16 | Content-Type da resposta de erro é application/problem+json | Qualquer erro | Header Content-Type application/problem+json | AUTO | LACUNA | P2 |
| ERR-17 | Campos do ProblemDetail (type, title, status, detail, instance, code, correlationId) | Inspecionar um 404 e um 409 | Todos presentes; instance = path; correlationId = header X-Correlation-Id | AUTO | PARCIAL: GlobalExceptionHandlerTest#businessExceptionBecomesProblemDetail... cobre status/instance/detail/code/correlationId (controller de teste); type e title não são assertados | P2 |
| ERR-18 | Rota inexistente | GET /nao-existe, GET /events (sem id), GET /reservations | 404 em ProblemDetail com code e correlationId. Provável 500 INTERNAL_ERROR (NoResourceFoundException capturada por handleAny) e stack no log a cada sonda (D1) | AUTO | LACUNA | P1 |
| ERR-19 | Método não permitido | PUT /events; DELETE /events/{id}; POST /reservations/{id}; GET /events/{id}/reservations | 405 com header Allow. Provável 500 (HttpRequestMethodNotSupportedException capturada por handleAny; D1) | AUTO | LACUNA | P1 |
| ERR-20 | Media type não suportado | POST com text/plain, application/xml e sem Content-Type | 415 (provável 500; D1) | AUTO | LACUNA | P1 |
| ERR-21 | Accept não atendido | GET /events/{id} com Accept: application/xml | 406 (provável 500 ou 200 JSON; verificar) | AUTO | LACUNA | P2 |
| ERR-22 | 500 real sem vazar stack (erro vindo do banco) | Provocar erro SQL não mapeado (ex.: NUL em name) | 500 INTERNAL_ERROR sem SQL, sem nomes de classe e sem stack | AUTO | LACUNA | P1 |
| ERR-23 | 503 em leituras (GET) com pool esgotado | Pool de 2 conexões retidas; GET /events/{id} e GET /reservations/{id} | 503 DATABASE_BUSY + Retry-After | AUTO | LACUNA | P2 |
| ERR-24 | X-Correlation-Id do cliente é devolvido e vai ao corpo do erro | GET com X-Correlation-Id custom | Header e correlationId iguais ao enviado | AUTO | COBERTO: EventApiTest#echoesCorrelationIdAndExposesInstanceId; GlobalExceptionHandlerTest#businessExceptionBecomesProblemDetailWithCodeAndCorrelationId | P0 |
| ERR-25 | X-Correlation-Id gerado quando ausente | Requisição sem o header | Header presente (UUID), igual ao correlationId do erro | AUTO | COBERTO: EventApiTest#generatesCorrelationIdWhenAbsent, GlobalExceptionHandlerTest#correlationIdIsGeneratedWhenAbsent | P1 |
| ERR-26 | X-Correlation-Id em branco ou com mais de 64 caracteres é descartado | Enviar "" / "   " / 65 chars | Novo UUID gerado; nunca 500 na gravação do histórico | AUTO | LACUNA | P1 |
| ERR-27 | X-Instance-Id em todas as respostas (sucesso e erro) | Qualquer chamada | Header presente e igual a INSTANCE_ID | AUTO | PARCIAL: EventApiTest#echoesCorrelationIdAndExposesInstanceId (não vazio) e GlobalExceptionHandlerTest (valor "local"); nenhum teste cobre 2xx de reserva nem a distinção api1/api2 por resposta (MIT só o usa como dado) | P2 |
| ERR-28 | Erros de validação com vários campos inválidos | POST {"name":"","capacity":0} | Resposta determinística (hoje getFieldError() devolve o primeiro, ordem não garantida; D12) | AUTO | LACUNA | P2 |

### 2.10 Resiliência e infraestrutura

| ID | Cenário | Passos resumidos | Resultado esperado | Tipo | Cobertura atual | Prioridade |
|---|---|---|---|---|---|---|
| INF-01 | Queda de uma API com o nginx no ar | docker compose stop api1; disparar GET/POST em loop | A maioria das requisições segue 200/201 via api2; POSTs nunca são reenviados pelo nginx (proxy_next_upstream off); pode haver 502 pontuais enquanto o nginx marca api1 como falha (verificar). Ao subir api1, volta ao rodízio | MANUAL | LACUNA | P0 |
| INF-02 | Restart do PostgreSQL com as APIs no ar | docker compose restart postgres | Durante a queda: 503 DATABASE_BUSY com Retry-After (falha de conexão/pool); após voltar, Hikari reconecta sem reiniciar as APIs; nenhum dado perdido; oversell zero | MANUAL | LACUNA | P1 |
| INF-03 | Postgres indisponível na subida das APIs | Subir apis sem postgres | Flyway/Hikari falham e o processo encerra (não há política de restart no Compose); com depends_on service_healthy isso não ocorre no fluxo normal | MANUAL | LACUNA | P2 |
| INF-04 | Subida simultânea das duas instâncias (Flyway) | Duas aplicações sobem juntas em banco vazio | Migrations V1..V4 aplicadas uma única vez; nenhuma falha | AUTO | COBERTO: MultiInstanceConcurrencyTest#bothInstancesMigratedTheSharedDatabaseExactlyOnce (subida paralela em @BeforeAll) | P0 |
| INF-05 | Migrations V1..V4 aplicadas com sucesso | Consultar flyway_schema_history | Versões 1,2,3,4 com success | AUTO | COBERTO: ConstraintsTest#flywayAppliedAllMigrations | P1 |
| INF-06 | Graceful shutdown com requisições em voo | docker compose stop api1 durante carga | Requisições em andamento terminam (20s de fase de shutdown), nenhuma reserva parcial, novas conexões recusadas. Atenção: stop_grace_period padrão do Compose é 10s (< 20s; D8) | MANUAL | LACUNA | P1 |
| INF-07 | Healthchecks do Compose | docker compose ps | postgres, api1, api2 healthy; nginx só sobe depois das apis | MANUAL | LACUNA | P1 |
| INF-08 | docker compose up do zero (down -v) | docker compose down -v; docker compose up --build | Sobe tudo; migrations aplicadas; /actuator/health UP; fluxo feliz via curl OK | MANUAL | LACUNA | P0 |
| INF-09 | Ordem de dependência | Observar logs de subida | api1/api2 só após postgres healthy; nginx só após ambas healthy | MANUAL | LACUNA | P1 |
| INF-10 | nginx repassa X-Correlation-Id do cliente e gera um quando ausente | curl com e sem o header via :8080 | Com header: devolvido igual; sem: valor gerado (request id do nginx, 32 hex ou UUID da app) presente no header e no corpo de erro | MANUAL | LACUNA | P1 |
| INF-11 | nginx distribui em round-robin | 40 GET em sequência | X-Instance-Id alterna api1/api2 | K6 | COBERTO: k6/booking.js#distribution (instance_hits{instance:api1} e {instance:api2} > 0) | P1 |
| INF-12 | nginx não reenvia POST para outra instância | Derrubar uma API no meio de POSTs | POST que cai na instância morta retorna erro ao cliente, nunca é duplicado; retry do cliente com a mesma chave é seguro | MANUAL | LACUNA | P1 |
| INF-13 | Container recriado com IP novo (nginx resolve upstream só na partida) | docker compose up -d --force-recreate api1; GET via nginx | Se o IP mudar, nginx pode falhar com 502 até um reload/restart (D7). Registrar o comportamento observado | MANUAL | LACUNA | P1 |
| INF-14 | Corpo acima de 1 MB via nginx | curl com corpo de 2 MB | 413 do nginx (HTML, fora do contrato ProblemDetail); direto na API não há limite do Tomcat para JSON | MANUAL | LACUNA | P2 |
| INF-15 | ./mvnw verify passa do zero em ambiente limpo | Clonar, instalar JDK 21 + Docker, rodar ./mvnw verify | Suíte verde com um comando (DoD). Não há pipeline de CI no repositório (sem .github/workflows) | MANUAL | LACUNA | P1 |
| INF-16 | Imagem roda como usuário não root; build sem testes | docker compose exec api1 id | uid não root | MANUAL | LACUNA | P2 |
| INF-17 | Lock timeout na linha quente | Transação externa segura o evento | 503 DATABASE_BUSY em POST; retry funciona | AUTO | COBERTO: DatabaseBusyTest#lockTimeoutOnPostReturns503LeavesNothingBehindAndRetryWithSameKeySucceeds | P0 |
| INF-18 | Statement timeout | lock_timeout 10s e statement_timeout 400ms | 503 DATABASE_BUSY | AUTO | COBERTO: DatabaseStatementTimeoutTest#statementTimeoutReturns503AndLeavesNothingBehind | P1 |
| INF-19 | Pool de conexões esgotado | Pool de 2; reter as 2 conexões | 503 DATABASE_BUSY nos POST/DELETE e recuperação depois | AUTO | COBERTO: DatabasePoolExhaustedTest#exhaustedPoolReturns503ThenRecovers | P0 |
| INF-20 | Falha do banco durante o ciclo do job não derruba a aplicação | Ver EXP-11 | Job continua no ciclo seguinte | AUTO | LACUNA | P1 |

### 2.11 Observabilidade

| ID | Cenário | Passos resumidos | Resultado esperado | Tipo | Cobertura atual | Prioridade |
|---|---|---|---|---|---|---|
| OBS-01 | reservations.created incrementa só após o commit | POST 201; ler /actuator/metrics/reservations.created | +1 por reserva criada; replay não incrementa | AUTO | LACUNA | P1 |
| OBS-02 | reservations.created não incrementa em rollback | POST 409 (sem estoque), 404 e 503 | Contador inalterado | AUTO | LACUNA | P1 |
| OBS-03 | reservations.cancelled incrementa só no cancelamento efetivo | DELETE efetivo, DELETE repetido, DELETE 409 | +1, +0, +0 | AUTO | LACUNA | P1 |
| OBS-04 | reservations.expired incrementa pelo tamanho do lote, após o commit | expireBatch com N vencidas | +N; lote vazio +0 | AUTO | LACUNA | P1 |
| OBS-05 | reservations.rejected{reason=insufficient_capacity} | POST 409 por estoque | +1 por resposta | AUTO | LACUNA | P1 |
| OBS-06 | reservations.rejected{reason=idempotency_conflict} | POST com mesma chave e request diferente | +1 | AUTO | LACUNA | P1 |
| OBS-07 | reservations.rejected{reason=invalid_state} | DELETE de reserva vencida/EXPIRED | +1 | AUTO | LACUNA | P1 |
| OBS-08 | reservations.rejected{reason=db_busy} | Lock timeout em POST e DELETE | +1 por resposta 503 | AUTO | COBERTO: DatabaseBusyTest#lockTimeoutOnPostReturns503LeavesNothingBehindAndRetryWithSameKeySucceeds e #lockTimeoutOnDeleteReturns503ThenDeleteSucceedsAndReturnsStockOnce (busyCount +1) | P1 |
| OBS-09 | /actuator/health responde 200 UP com o banco no ar e 503 DOWN com o banco fora | GET /actuator/health | 200 {"status":"UP"}; com Postgres parado: 503 | MANUAL | LACUNA | P1 |
| OBS-10 | Somente health, info e metrics expostos | GET /actuator/env, /actuator/beans, /actuator/heapdump, /actuator/loggers | 404 (não expostos) | AUTO | LACUNA | P1 |
| OBS-11 | /actuator/metrics/reservations.* acessível | GET /actuator/metrics/reservations.created | 200 com measurements | AUTO | LACUNA | P2 |
| OBS-12 | Swagger UI acessível | GET /swagger-ui.html | 200/302 para /swagger-ui/index.html | AUTO | LACUNA | P1 |
| OBS-13 | OpenAPI coerente com o contrato real | GET /v3/api-docs | 5 operações (paths/métodos do PDF), header Idempotency-Key required em POST reservations, respostas 201/400/404/409/503 documentadas, exemplos de ProblemDetail | AUTO | LACUNA | P1 |
| OBS-14 | Logs com correlationId em todas as linhas de uma requisição | Enviar X-Correlation-Id conhecido e ler docker compose logs | Linhas reservation requested, capacity acquired, commit completed com [correlationId] | MANUAL | LACUNA | P2 |
| OBS-15 | Logs do job com correlation id job-xxxxxxxx | Observar expired batch n=... no log | correlationId começa com job- | MANUAL | LACUNA | P2 |

### 2.12 Multi-instância

| ID | Cenário | Passos resumidos | Resultado esperado | Tipo | Cobertura atual | Prioridade |
|---|---|---|---|---|---|---|
| MUL-01 | I3 em 2 instâncias (200 requisições alternadas, cap 50) | 200 POST distribuídos entre duas aplicações completas | 50 x 201, 150 x 409, available 0, ambas as instâncias no histórico | AUTO | COBERTO: MultiInstanceConcurrencyTest#i3_twoHundredRequestsSplitAcrossTwoInstancesNeverOversell | P0 |
| MUL-02 | Idempotência entre instâncias | Ver IDP-16 | Uma reserva | AUTO | COBERTO: MultiInstanceConcurrencyTest#idempotencyHoldsAcrossInstances | P0 |
| MUL-03 | Expiração em 2 instâncias | Ver EXP-09 | Cada reserva expira uma vez | AUTO | COBERTO: MultiInstanceConcurrencyTest#expirationJobsOfBothInstancesExpireEachReservationOnceWhileCancellationsRun | P0 |
| MUL-04 | Migração concorrente (Flyway) | Ver INF-04 | Migrações sem duplicidade | AUTO | COBERTO: MultiInstanceConcurrencyTest#bothInstancesMigratedTheSharedDatabaseExactlyOnce | P0 |
| MUL-05 | Cache divergente entre instâncias | Cache ligado nas duas; GET em A (popula); POST reserva em B; GET em A e em B; esperar TTL | A (dentro do TTL) mostra valor antigo; B mostra novo; após o TTL ambas convergem. Oscilação 48 -> 50 -> 48 é esperada | AUTO | LACUNA | P1 |
| MUL-06 | Leitura/cancelamento em instância diferente da criação | POST em A; GET e DELETE em B | GET 200; DELETE 204; GET em A mostra CANCELLED | AUTO | LACUNA | P1 |
| MUL-07 | Distribuição real via nginx (processos separados) | k6 cenário distribution | api1 e api2 recebem tráfego | K6 | COBERTO: k6/booking.js#distribution | P1 |
| MUL-08 | Prova de oversell com containers reais | k6 stampede no Compose | Ver STK-10 | K6 | COBERTO: k6/booking.js#stampede | P0 |
| MUL-09 | Dois jobs, uma reserva travada por DELETE em outra instância | DELETE em A segurando a linha; job em B | Job de B pula a linha (SKIP LOCKED) sem esperar; sem deadlock; estoque correto | AUTO | PARCIAL: MultiInstanceConcurrencyTest mistura cancelamentos e jobs, mas com reservas válidas e vencidas distintas (não a mesma linha) | P2 |
| MUL-10 | Relógio do banco como fonte única de tempo | Instâncias com relógios diferentes | Nenhuma regra depende de Instant.now() da aplicação | AUTO | LACUNA | P2 |

### 2.13 Segurança e robustez básica (sem autenticação no escopo)

| ID | Cenário | Passos resumidos | Resultado esperado | Tipo | Cobertura atual | Prioridade |
|---|---|---|---|---|---|---|
| SEC-01 | Corpo gigante (5 MB) em POST /events | POST com name de 5 MB | Sem OOM nem timeout: direto na API o corpo é lido e rejeitado com 400 INVALID_EVENT_NAME; via nginx, 413 (client_max_body_size padrão de 1 MB) | AUTO | LACUNA | P1 |
| SEC-02 | JSON profundamente aninhado (5.000 níveis) | POST com [[[[...]]]] | 400 MALFORMED_REQUEST (limite de profundidade do Jackson); sem StackOverflow/500 | AUTO | LACUNA | P1 |
| SEC-03 | Idempotency-Key enorme (16 KB) | POST com header de 16 KB | Rejeitado pelo Tomcat (400, limite de header de 8 KB) antes do controller; aplicação segue saudável | MANUAL | LACUNA | P1 |
| SEC-04 | SQL injection no name | POST name "x'); DROP TABLE events;--" | 201; name armazenado literalmente; tabelas intactas (JdbcClient parametrizado) | AUTO | LACUNA | P1 |
| SEC-05 | SQL injection no path | GET /events/1%27%20OR%20%271%27=%271 | 400 INVALID_ID_FORMAT | AUTO | LACUNA | P1 |
| SEC-06 | SQL injection na Idempotency-Key | POST com chave k'; DROP TABLE idempotency_keys;-- | 201; chave gravada literalmente | AUTO | LACUNA | P1 |
| SEC-07 | UUID em maiúsculas no path | GET /events/{UUID MAIÚSCULO}; POST reserva idem | 200/201 equivalente ao minúsculo; hash canônico em minúsculas (ver IDP-12); Location em minúsculas | AUTO | LACUNA | P1 |
| SEC-08 | UUIDs em formatos exóticos | GET /events/1-1-1-1-1, sem hifens, com chaves {..} | Formato estrito seria 400. UUID.fromString do Java aceita "1-1-1-1-1" (vira um UUID válido, 404); sem hifens e com chaves => 400. Documentar (D20) | AUTO | LACUNA | P2 |
| SEC-09 | Caracteres de controle no name (\u0001, \n, \t) | POST com cada um | Aceitos ou rejeitados de forma definida; nunca 500 | AUTO | LACUNA | P2 |
| SEC-10 | HTML/script no name | POST name "<script>alert(1)</script>" | 201; devolvido escapado em JSON com Content-Type application/json (sem XSS refletido) | AUTO | LACUNA | P2 |
| SEC-11 | X-Correlation-Id com caracteres estranhos | Enviar %0d%0a, aspas, unicode | Sem injeção de header (o servidor recusa CR/LF); valor aceito segue ao log/histórico até 64 chars (D18) | MANUAL | LACUNA | P2 |
| SEC-12 | Endpoints sensíveis do Actuator | Ver OBS-10 | 404 | AUTO | LACUNA | P1 |
| SEC-13 | Path traversal em ids | GET /reservations/..%2f..%2fetc | 400 INVALID_ID_FORMAT ou 404; nada fora do contrato | AUTO | LACUNA | P2 |
| SEC-14 | Sem autenticação (fora do escopo): qualquer cliente pode cancelar/consultar qualquer reserva e reutilizar chaves alheias | GET/DELETE com UUID de outra sessão; mesma chave de outro cliente | Comportamento aceito pelo escopo do PDF; chave de idempotência é global (D4) | MANUAL | LACUNA | P2 |

## 3. Roteiros MANUAIS

Pré-requisitos: Docker/Compose, `curl` e `jq` (em Windows, use Git Bash ou WSL). Todos os comandos rodam na raiz do projeto (`C:\teste igor`) contra o nginx em `http://localhost:8080`. Convenções:

```bash
BASE=http://localhost:8080
PSQL="docker compose exec -T postgres psql -U flashbooking -d flashbooking -At -c"

# Cria um evento e exporta EVENT (id). Uso: new_event <capacidade>
new_event() { curl -s -X POST $BASE/events -H 'Content-Type: application/json' \
  -d "{\"name\":\"manual $(date +%s)\",\"capacity\":$1}" | jq -r .id; }

# Reserva. Uso: reserve <eventId> <quantity> <chave>
reserve() { curl -s -i -X POST $BASE/events/$1/reservations -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $3" -d "{\"quantity\":$2}"; }

# Invariante de estoque do evento (total_capacity = available + soma PENDING). Uso: invariant <eventId>
invariant() { $PSQL "SELECT e.total_capacity, e.available, COALESCE(SUM(r.quantity) FILTER (WHERE r.status='PENDING'),0) AS pending_sum, (e.total_capacity = e.available + COALESCE(SUM(r.quantity) FILTER (WHERE r.status='PENDING'),0)) AS ok FROM events e LEFT JOIN reservations r ON r.event_id=e.id WHERE e.id='$1' GROUP BY e.id"; }
```

Sem `jq`: extraia o id com `sed -E 's/.*"id":"([^"]+)".*/\1/'`.

### M-01 - Subida do zero (`docker compose up` com `down -v`), healthchecks e dependências (INF-07, INF-08, INF-09, INF-16)

```bash
docker compose down -v
docker compose up --build -d
# acompanhar a ordem de subida (postgres -> apis -> nginx)
docker compose ps
until [ "$(docker inspect -f '{{.State.Health.Status}}' $(docker compose ps -q api1))" = "healthy" ]; do sleep 3; done
until [ "$(docker inspect -f '{{.State.Health.Status}}' $(docker compose ps -q api2))" = "healthy" ]; do sleep 3; done
docker compose ps
curl -s -o /dev/null -w "health=%{http_code}\n" $BASE/actuator/health
curl -s $BASE/actuator/health
# migrations
$PSQL "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank"
# usuário não root e fluxo feliz
docker compose exec -T api1 id
EVENT=$(new_event 50); echo $EVENT
reserve $EVENT 2 m01-key-1 | head -n 12
```

Resultado esperado:
- `docker compose ps` mostra `postgres`, `api1`, `api2` como `healthy` e `nginx` como `Up`; o nginx só aparece depois de api1 e api2 saudáveis (confirme com `docker compose logs --timestamps` comparando o horário do primeiro log do nginx com o "Started FlashBookingApplication" das apis).
- `health=200` e corpo `{"status":"UP"}`.
- Flyway: linhas `1|t`, `2|t`, `3|t`, `4|t` (uma única vez, apesar de as duas apis subirem juntas). Nos logs das apis, apenas uma delas aplica as migrations; a outra as encontra aplicadas.
- `id` do usuário da api1 não é `uid=0`.
- Fluxo feliz: `201 Created` com `Location: /reservations/...`, `X-Instance-Id: api1|api2`, `X-Correlation-Id` preenchido.
- Variação (INF-03): `docker compose down -v && docker compose up -d api1` ainda sobe o postgres por dependência (`depends_on`); para simular banco fora, `docker compose stop postgres` e `docker compose up -d --no-deps api1` deve fazer a api encerrar com erro de conexão (não há `restart:` no Compose). Registrar o comportamento.

### M-02 - Queda de uma API com o nginx no ar (INF-01, INF-12)

```bash
EVENT=$(new_event 50)
docker compose stop api1
echo "--- GETs com api1 parada"
for i in $(seq 1 20); do curl -s -o /dev/null -w "%{http_code} " $BASE/events/$EVENT; done; echo
echo "--- POSTs com api1 parada"
for i in $(seq 1 10); do curl -s -o /dev/null -w "%{http_code} " -X POST $BASE/events/$EVENT/reservations \
  -H 'Content-Type: application/json' -H "Idempotency-Key: m02-$i" -d '{"quantity":1}'; done; echo
docker compose logs --tail 20 nginx
invariant $EVENT

docker compose start api1
until [ "$(docker inspect -f '{{.State.Health.Status}}' $(docker compose ps -q api1))" = "healthy" ]; do sleep 3; done
echo "--- distribuicao após api1 voltar"
for i in $(seq 1 20); do curl -s -D - -o /dev/null $BASE/events/$EVENT | grep -i '^x-instance-id'; done | sort | uniq -c

echo "--- retry seguro: repetir chaves que falharam"
for i in $(seq 1 10); do curl -s -o /dev/null -w "%{http_code} " -X POST $BASE/events/$EVENT/reservations \
  -H 'Content-Type: application/json' -H "Idempotency-Key: m02-$i" -d '{"quantity":1}'; done; echo
invariant $EVENT
```

Resultado esperado:
- Com api1 parada, a maioria das respostas é 200/201 vinda da api2. Pode haver 502 pontuais (nginx marca a upstream como falha por `fail_timeout`; a configuração `proxy_next_upstream off` impede o reenvio transparente, inclusive de GET). Registre quantos 502 ocorrem e se param após a primeira falha (comportamento a verificar, D7).
- Nenhum POST é duplicado: a soma de reservas criadas no banco nunca passa do número de 201 recebidos (conferir com `SELECT count(*) FROM reservations WHERE event_id='$EVENT'`).
- Após `start`, `X-Instance-Id` volta a alternar (`api1` e `api2` com contagens próximas).
- No replay final, chaves que já tinham 201 voltam 201 com `Idempotent-Replayed: true` (se repetidas com `-i`); chaves que caíram em 502 criam a reserva agora (201) sem duplicar. `invariant` com `ok = t` nas duas conferências.

### M-03 - Restart do PostgreSQL com as APIs no ar (INF-02, OBS-09)

```bash
EVENT=$(new_event 500)
# tráfego contínuo em segundo plano, registrando status e horário
( i=0; while true; do i=$((i+1)); printf "%s %s\n" "$(date +%T)" \
  "$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/events/$EVENT/reservations \
     -H 'Content-Type: application/json' -H "Idempotency-Key: m03-$i" -d '{"quantity":1}')"; sleep 0.3; done ) > /tmp/m03.log &
LOOP=$!
sleep 5
docker compose restart postgres
sleep 30
kill $LOOP
awk '{print $2}' /tmp/m03.log | sort | uniq -c
curl -s -o /dev/null -w "health=%{http_code}\n" $BASE/actuator/health
invariant $EVENT
```

Além disso, durante a queda (repita em um terminal separado logo após `docker compose stop postgres`): `curl -i $BASE/actuator/health` deve devolver 503 `{"status":"DOWN"}`; e `curl -i -X POST ...reservations` deve devolver 503 `DATABASE_BUSY` com `Retry-After: 1` (falha ao obter conexão após o `connection-timeout` de 3s).

Resultado esperado:
- Distribuição de status: majoritariamente 201, uma janela de 503 (`DATABASE_BUSY`) enquanto o Postgres está fora e 201 novamente depois, sem reiniciar as apis (Hikari reconecta).
- Requisições que estavam em voo no instante exato do restart podem devolver 500 (o SQLState 57P01/08006 não está em `BUSY_SQL_STATES`; D21). Registrar se ocorrerem.
- `health=200` ao final; `invariant` com `ok = t`; o volume `pgdata` preserva os dados (reservas anteriores continuam consultáveis com GET).

### M-04 - Graceful shutdown durante requisições (INF-06)

```bash
EVENT=$(new_event 1000)
# 300 requisições em paralelo (xargs -P 50), em segundo plano
seq 1 300 | xargs -P 50 -I{} sh -c 'curl -s -o /dev/null -w "%{http_code} " -X POST '$BASE'/events/'$EVENT'/reservations \
  -H "Content-Type: application/json" -H "Idempotency-Key: m04-{}" -d "{\"quantity\":1}"' > /tmp/m04.out &
sleep 1
docker compose stop -t 30 api1
wait
tr ' ' '\n' < /tmp/m04.out | sort | uniq -c
docker compose logs api1 | grep -iE "graceful|shutdown" | tail
invariant $EVENT
```

Resultado esperado:
- Logs da api1 com `Commencing graceful shutdown. Waiting for active requests to complete` e, em seguida, `Graceful shutdown complete`.
- Nenhuma reserva parcial (`invariant` com `ok = t`). Requisições já aceitas pela api1 terminam com 201/409; novas requisições enviadas pelo nginx à api1 depois do stop podem falhar (502) e são repetíveis com a mesma chave.
- Observação D8: `docker compose stop -t 30` foi usado de propósito; sem `-t`, o Compose usa 10s (menor que os 20s de `timeout-per-shutdown-phase`). Repetir com `docker compose stop api1` e ver se os logs mostram o shutdown completo antes do SIGKILL.

### M-05 - Correlation id ponta a ponta e inspeção de logs (INF-10, OBS-14, HST-11, ERR-26, SEC-11)

```bash
EVENT=$(new_event 10)
# 1) id do cliente é propagado pelo nginx, devolvido no header e gravado no histórico
curl -s -i -X POST $BASE/events/$EVENT/reservations -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: demo-corr-123' -H 'Idempotency-Key: m05-1' -d '{"quantity":1}' | grep -iE '^(HTTP|x-correlation-id|x-instance-id)'
docker compose logs api1 api2 | grep 'demo-corr-123'
$PSQL "SELECT action, correlation_id, instance_id FROM reservation_history WHERE correlation_id='demo-corr-123'"

# 2) erro: o correlationId do corpo é igual ao header
curl -s -i $BASE/events/00000000-0000-0000-0000-000000000000 -H 'X-Correlation-Id: demo-corr-404' | grep -iE '^(HTTP|x-correlation-id)|correlationId'

# 3) sem header: nginx gera (request_id) e a API devolve o mesmo
curl -s -i $BASE/events/$EVENT | grep -i '^x-correlation-id'

# 4) 65 caracteres: a API descarta e gera um UUID (o valor do nginx é repassado, então a API vê 65 chars)
LONG=$(head -c 65 /dev/zero | tr '\0' 'a')
curl -s -i $BASE/events/$EVENT -H "X-Correlation-Id: $LONG" | grep -i '^x-correlation-id'

# 5) 64 caracteres é aceito e gravado inteiro no histórico
OK64=$(head -c 64 /dev/zero | tr '\0' 'b')
curl -s -o /dev/null -w "%{http_code}\n" -X POST $BASE/events/$EVENT/reservations -H 'Content-Type: application/json' \
  -H "X-Correlation-Id: $OK64" -H 'Idempotency-Key: m05-2' -d '{"quantity":1}'
$PSQL "SELECT length(correlation_id) FROM reservation_history WHERE correlation_id='$OK64'"
```

Resultado esperado:
- (1) `201`, `X-Correlation-Id: demo-corr-123`, `X-Instance-Id` api1 ou api2; os logs mostram linhas `reservation requested`, `capacity acquired` e `commit completed` com `[demo-corr-123]`; o histórico tem `CREATED|demo-corr-123|api1` (ou api2).
- (2) 404 com `correlationId` = `demo-corr-404` no corpo e no header.
- (3) header presente (32 hex do nginx quando o cliente não envia nada).
- (4) header diferente do enviado e com 36 caracteres (UUID gerado); sem erro 500.
- (5) `201` e `length = 64`.

### M-06 - Reinício da aplicação com reservas vencidas pendentes (EXP-12)

```bash
EVENT=$(new_event 50)
docker compose stop api1 api2
$PSQL "INSERT INTO reservations (id, event_id, quantity, status, expires_at, created_at)
       SELECT gen_random_uuid(), '$EVENT', 1, 'PENDING', NOW() - interval '1 hour' + g * interval '1 second', NOW() - interval '2 hours'
       FROM generate_series(1,30) g"
$PSQL "UPDATE events SET available = available - 30 WHERE id='$EVENT'"
invariant $EVENT                                   # ok = t, available = 20
docker compose start api1 api2
until [ "$(docker inspect -f '{{.State.Health.Status}}' $(docker compose ps -q api1))" = "healthy" ]; do sleep 2; done
sleep 12
$PSQL "SELECT status, count(*) FROM reservations WHERE event_id='$EVENT' GROUP BY status"
$PSQL "SELECT action, count(*) FROM reservation_history WHERE event_id='$EVENT' GROUP BY action"
invariant $EVENT
```

Resultado esperado: após a subida (primeiro ciclo do job, até 5s), as 30 reservas passam a `EXPIRED`, `available = 50`, histórico com 30 linhas `EXPIRED` (`correlation_id` `job-xxxxxxxx`) e `invariant` com `ok = t`. Antes do start, o `GET /reservations/{id}` de qualquer uma delas (quando a app voltar) já mostra `EXPIRED` (status efetivo).

### M-07 - Container recriado e resolução de DNS do nginx (INF-13)

```bash
EVENT=$(new_event 20)
ip() { docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $(docker compose ps -q $1); }
echo "api1 antes: $(ip api1)"
docker compose up -d --force-recreate api1
until [ "$(docker inspect -f '{{.State.Health.Status}}' $(docker compose ps -q api1))" = "healthy" ]; do sleep 3; done
echo "api1 depois: $(ip api1)"
for i in $(seq 1 20); do curl -s -o /dev/null -w "%{http_code} " $BASE/events/$EVENT; done; echo
# se aparecerem 502, recarregar o nginx e repetir
docker compose exec -T nginx nginx -s reload
for i in $(seq 1 20); do curl -s -o /dev/null -w "%{http_code} " $BASE/events/$EVENT; done; echo
```

Resultado esperado: se o IP não mudou, todos 200. Se mudou, o nginx (que resolve `api1`/`api2` apenas ao iniciar) deve devolver 502 para a metade das requisições até o `reload`; após o reload, 100% 200. Registrar o resultado observado (D7/D17).

### M-08 - Corpo grande e limite do nginx (INF-14, SEC-01)

```bash
head -c 2000000 /dev/zero | tr '\0' 'a' > /tmp/big.txt
{ printf '{"name":"'; cat /tmp/big.txt; printf '","capacity":5}'; } > /tmp/big.json
echo "--- via nginx"
curl -s -o /dev/null -w "%{http_code}\n" -X POST $BASE/events -H 'Content-Type: application/json' --data-binary @/tmp/big.json
echo "--- direto na API (por dentro do container)"
docker compose exec -T api1 sh -c 'curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/events -H "Content-Type: application/json" --data-binary @-' < /tmp/big.json
curl -s -o /dev/null -w "saude=%{http_code}\n" $BASE/actuator/health
```

Resultado esperado: via nginx `413` (HTML do nginx, fora do contrato ProblemDetail; `client_max_body_size` padrão 1 MB); direto na API `400` com `INVALID_EVENT_NAME` (o corpo é lido por inteiro, a validação de tamanho do `name` rejeita); a aplicação segue saudável (`saude=200`) e sem `OutOfMemoryError` nos logs.

### M-09 - Header enorme, Actuator e Swagger (SEC-03, OBS-09, OBS-10, OBS-12, OBS-13)

```bash
EVENT=$(new_event 5)
KEY=$(head -c 16000 /dev/zero | tr '\0' 'k')
echo "--- Idempotency-Key de 16 KB via nginx"
curl -s -o /dev/null -w "%{http_code}\n" -X POST $BASE/events/$EVENT/reservations -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d '{"quantity":1}'
echo "--- idem, direto na API"
docker compose exec -T api1 sh -c 'curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/events/'$EVENT'/reservations -H "Content-Type: application/json" -H "Idempotency-Key: '$KEY'" -d "{\"quantity\":1}"'
echo "--- Actuator exposto"
for p in health info metrics metrics/reservations.created env beans heapdump loggers threaddump; do
  printf "%-28s %s\n" "/actuator/$p" "$(curl -s -o /dev/null -w '%{http_code}' $BASE/actuator/$p)"
done
echo "--- Swagger / OpenAPI"
curl -s -o /dev/null -w "swagger-ui.html=%{http_code}\n" -L $BASE/swagger-ui.html
curl -s $BASE/v3/api-docs | jq '.paths | to_entries[] | {path: .key, metodos: (.value | keys)}'
curl -s $BASE/v3/api-docs | jq '.paths["/events/{id}/reservations"].post.parameters[] | select(.name=="Idempotency-Key")'
curl -s $BASE/v3/api-docs | jq '.paths["/reservations/{id}"].delete.responses | keys'
```

Resultado esperado:
- Header de 16 KB: `400` (nginx: "Request Header Or Cookie Too Large", ou Tomcat, limite de 8 KB), nunca 500; aplicação saudável.
- Actuator: `health`, `info`, `metrics`, `metrics/reservations.created` respondem 200 (este último após ao menos uma reserva naquela instância); `env`, `beans`, `heapdump`, `loggers`, `threaddump` respondem 404.
- Swagger: `200` após o redirect; `/v3/api-docs` lista exatamente `/events`, `/events/{id}`, `/events/{id}/reservations`, `/reservations/{id}` (GET e DELETE); o parâmetro `Idempotency-Key` é `in: header` e `required: true`; o DELETE documenta `204`, `400`, `404`, `409`.

### M-10 - Expiração ponta a ponta com TTL curto, sem manipular o banco (EXP-14, HST-12, OBS-15, EXP-13)

```bash
cat > /tmp/ttl.override.yml <<'EOF'
services:
  api1:
    environment:
      BOOKING_RESERVATION_TTL: 20s
      BOOKING_RESERVATION_EXPIRATION_JOB_DELAY: 2s
  api2:
    environment:
      BOOKING_RESERVATION_TTL: 20s
      BOOKING_RESERVATION_EXPIRATION_JOB_DELAY: 2s
EOF
docker compose -f docker-compose.yml -f /tmp/ttl.override.yml up -d
until [ "$(docker inspect -f '{{.State.Health.Status}}' $(docker compose ps -q api1))" = "healthy" ]; do sleep 3; done
until [ "$(docker inspect -f '{{.State.Health.Status}}' $(docker compose ps -q api2))" = "healthy" ]; do sleep 3; done

EVENT=$(new_event 5)
RES=$(reserve $EVENT 5 m10-1 | sed -n '/^{/p' | jq -r .id); echo $RES
curl -s $BASE/reservations/$RES | jq '{status, createdAt, expiresAt}'       # PENDING, expiresAt = createdAt + 20s
curl -s -o /dev/null -w "novo POST com evento esgotado: %{http_code}\n" -X POST $BASE/events/$EVENT/reservations -H 'Content-Type: application/json' -H 'Idempotency-Key: m10-2' -d '{"quantity":1}'
sleep 24
curl -s $BASE/reservations/$RES | jq .status                                  # EXPIRED
sleep 4
$PSQL "SELECT status FROM reservations WHERE id='$RES'"                        # EXPIRED (físico)
sleep 2
curl -s $BASE/events/$EVENT | jq .available                                    # 5 (após o TTL do cache de 1s)
curl -s -o /dev/null -w "DELETE vencida: %{http_code}\n" -X DELETE $BASE/reservations/$RES
curl -s -o /dev/null -w "novo POST apos expirar: %{http_code}\n" -X POST $BASE/events/$EVENT/reservations -H 'Content-Type: application/json' -H 'Idempotency-Key: m10-3' -d '{"quantity":1}'
$PSQL "SELECT action, correlation_id, instance_id FROM reservation_history WHERE reservation_id='$RES' ORDER BY id"
docker compose logs api1 api2 | grep 'expired batch'
invariant $EVENT

# restaurar o TTL padrão
docker compose up -d
```

Resultado esperado: `expiresAt - createdAt = 20s`; POST em evento esgotado devolve 409; após ~20s `GET` já mostra `EXPIRED` (status efetivo) e, até 2-4s depois, o estado físico muda para `EXPIRED` e o estoque volta (`available = 5`); `DELETE` devolve 409 `INVALID_RESERVATION_STATE`; novo POST devolve 201; o histórico mostra `CREATED|<correlação do POST>|apiX` e `EXPIRED|job-xxxxxxxx|apiX`; os logs mostram `expired batch n=1 events=1` em apenas uma das instâncias; `invariant` com `ok = t`. As métricas `reservations.expired` ficam por instância (consultar diretamente api1/api2 por `docker compose exec`, pois via nginx o rodízio alterna; D19).

## 4. Matriz de rastreabilidade

Requisitos do PDF (`Case BackEnd 1.pdf`) e itens do DoD (PLANO §13) mapeados para os cenários que os provam. A coluna "Situação" resume o status dos cenários vinculados (COBERTO/PARCIAL/LACUNA). "README" é verificado por revisão documental (as lacunas D10 e D3 afetam esse requisito); os cenários listados nessa linha são os de observabilidade documentada.

| Requisito | Cenários | Situação (C/P/L) | Leitura |
|---|---|---|---|
| RF1 - POST /events (criar evento) | EVT-01, EVT-02, EVT-03, EVT-04, EVT-05, EVT-06, EVT-07, EVT-08, EVT-09, EVT-10, EVT-11, EVT-12, EVT-13, EVT-14, EVT-15, EVT-16, EVT-17, EVT-18, EVT-19, EVT-20, EVT-21, EVT-22, EVT-23, EVT-24, ERR-04, ERR-05, SEC-01, SEC-04, SEC-09, SEC-10 | 11 / 2 / 17 | Núcleo provado; ver lacunas listadas na seção 5. |
| RF2 - GET /events/:id (consultar disponibilidade) | EVT-25, EVT-26, EVT-27, EVT-28, EVT-29, EVT-30, MUL-05, SEC-07, ERR-06, ERR-08 | 6 / 2 / 2 | Núcleo provado; ver lacunas listadas na seção 5. |
| RF3 - POST /events/:id/reservations (reservar) | RSV-01, RSV-02, RSV-03, RSV-04, RSV-05, RSV-06, RSV-07, RSV-08, RSV-09, RSV-10, RSV-11, RSV-12, RSV-13, RSV-14, RSV-15, RSV-16, RSV-17, RSV-18, RSV-19, RSV-20, RSV-21, RSV-22, RSV-23, RSV-24, RSV-25, RSV-26, STK-01, STK-02, STK-03, STK-04, STK-05, STK-06, STK-07, STK-08, STK-09, STK-10, STK-11, STK-12, IDP-01, IDP-02, IDP-03, IDP-04, IDP-05, IDP-06, IDP-07, IDP-08, IDP-09, IDP-10, IDP-11, IDP-12, IDP-13, IDP-14, IDP-15, IDP-16, IDP-17, IDP-18, IDP-19, IDP-20, IDP-21, IDP-22, ERR-01, ERR-02, ERR-03, ERR-10, ERR-12, HST-01 | 39 / 3 / 24 | Núcleo provado; ver lacunas listadas na seção 5. |
| RF4 - GET /reservations/:id (consultar reserva) | QRY-01, QRY-02, QRY-03, QRY-04, QRY-05, QRY-06, QRY-07, QRY-08, QRY-09, QRY-10, QRY-11, ERR-09 | 8 / 1 / 3 | Núcleo provado; ver lacunas listadas na seção 5. |
| RF5 - DELETE /reservations/:id (cancelar) | CAN-01, CAN-02, CAN-03, CAN-04, CAN-05, CAN-06, CAN-07, CAN-08, CAN-09, CAN-10, CAN-11, CAN-12, CAN-13, CAN-14, CAN-15, CAN-16, CAN-17, CAN-18, CAN-19, ERR-11, HST-02 | 19 / 0 / 2 | Núcleo provado; ver lacunas listadas na seção 5. |
| RNF1 - Múltiplas instâncias simultâneas da API | MUL-01, MUL-02, MUL-03, MUL-04, MUL-05, MUL-06, MUL-07, MUL-08, MUL-09, MUL-10, INF-01, INF-04, INF-11, INF-12, INF-13, STK-10, IDP-16, IDP-17, QRY-11, CAN-16, EXP-09 | 13 / 1 / 7 | Núcleo provado; ver lacunas listadas na seção 5. |
| RNF2 - Nunca permitir oversell | STK-01, STK-02, STK-03, STK-04, STK-05, STK-06, STK-07, STK-08, STK-09, STK-10, STK-11, STK-12, CAN-09, MUL-01, MUL-08, RSV-21, RSV-22, RSV-23, IDP-14 | 11 / 3 / 5 | Núcleo provado; ver lacunas listadas na seção 5. |
| RNF3 - Expiração automática de reservas pendentes | EXP-01, EXP-02, EXP-03, EXP-04, EXP-05, EXP-06, EXP-07, EXP-08, EXP-09, EXP-10, EXP-11, EXP-12, EXP-13, EXP-14, EXP-15, EXP-16, EXP-17, EXP-18, EXP-19, QRY-02, QRY-04, CAN-04, CAN-05, CAN-11, CAN-12, CAN-13, HST-03, OBS-04, MUL-03 | 19 / 1 / 9 | Núcleo provado; ver lacunas listadas na seção 5. |
| RNF4 - Idempotência | IDP-01, IDP-02, IDP-03, IDP-04, IDP-05, IDP-06, IDP-07, IDP-08, IDP-09, IDP-10, IDP-11, IDP-12, IDP-13, IDP-14, IDP-15, IDP-16, IDP-17, IDP-18, IDP-19, IDP-20, IDP-21, IDP-22, RSV-12, RSV-13, RSV-15, RSV-16, RSV-17, RSV-18, OBS-06, MUL-02 | 17 / 0 / 13 | Núcleo provado; ver lacunas listadas na seção 5. |
| RNF5 - Consistência eventual para disponibilidade | EVT-28, EVT-29, EVT-30, MUL-05, EXP-16, INF-11 | 2 / 2 / 2 | Núcleo provado; ver lacunas listadas na seção 5. |
| RNF6 - Tratamento explícito de erros | ERR-01, ERR-02, ERR-03, ERR-04, ERR-05, ERR-06, ERR-07, ERR-08, ERR-09, ERR-10, ERR-11, ERR-12, ERR-13, ERR-14, ERR-15, ERR-16, ERR-17, ERR-18, ERR-19, ERR-20, ERR-21, ERR-22, ERR-23, ERR-24, ERR-25, ERR-26, ERR-27, ERR-28, EVT-20, EVT-17, RSV-10, INF-02, SEC-01, SEC-02, SEC-03, SEC-04, SEC-05, SEC-06, SEC-07, SEC-08, SEC-09, SEC-10, SEC-11, SEC-12, SEC-13, SEC-14, OBS-07, OBS-08 | 18 / 2 / 28 | Núcleo provado; ver lacunas listadas na seção 5. |
| Restrição - Docker Compose | INF-01, INF-02, INF-06, INF-07, INF-08, INF-09, INF-10, INF-13, INF-16 | 0 / 0 / 9 | Sem prova automatizada. |
| Restrição - Testes automatizados | INF-15, INF-04, INF-05, STK-10, IDP-17, INF-17, INF-18, INF-19 | 7 / 0 / 1 | Núcleo provado; ver lacunas listadas na seção 5. |
| Restrição - README (instruções, decisões, trade-offs, evoluções) | OBS-12, OBS-13, IDP-22 | 0 / 0 / 3 | Sem prova automatizada. |
| DoD/PLANO - Histórico append-only na mesma transação (I16) | HST-01, HST-02, HST-03, HST-04, HST-05, HST-06, HST-07, HST-08, HST-09, HST-10, HST-11, HST-12 | 9 / 0 / 3 | Núcleo provado; ver lacunas listadas na seção 5. |
| DoD/PLANO - 503 DATABASE_BUSY + Retry-After (I14) | ERR-13, ERR-15, ERR-23, INF-17, INF-18, INF-19, OBS-08, CAN-14, IDP-07, IDP-08, INF-02 | 9 / 0 / 2 | Núcleo provado; ver lacunas listadas na seção 5. |
| DoD/PLANO - Observabilidade (métricas, health, Swagger, correlation id) | OBS-01, OBS-02, OBS-03, OBS-04, OBS-05, OBS-06, OBS-07, OBS-08, OBS-09, OBS-10, OBS-11, OBS-12, OBS-13, OBS-14, OBS-15, ERR-24, ERR-25, ERR-26, ERR-27, HST-11 | 3 / 1 / 16 | Núcleo provado; ver lacunas listadas na seção 5. |

## 5. Lacunas priorizadas (somente LACUNA e PARCIAL)

Ordenadas por prioridade (P0, P1, P2) e, dentro delas, PARCIAL antes de LACUNA (esforço menor) e depois por ID. "Como testar" traz o tipo, a classe sugerida e os asserts essenciais.

| # | ID | Prioridade | Status | Cenário | Como testar (tipo, classe sugerida, asserts) |
|---|---|---|---|---|---|
| 1 | RSV-22 | P0 | PARCIAL | quantity maior que o disponível (ainda há estoque) | Tipo AUTO. ReservationValidationTest: disponível 3, POST 4; assertar 409 + code INSUFFICIENT_CAPACITY + available inalterado + invariante. |
| 2 | INF-01 | P0 | LACUNA | Queda de uma API com o nginx no ar | Tipo MANUAL. Roteiro M-02. |
| 3 | INF-08 | P0 | LACUNA | docker compose up do zero (down -v) | Tipo MANUAL. Roteiro M-01. |
| 4 | RSV-23 | P1 | PARCIAL | quantity maior que a capacidade total | Tipo AUTO. ReservationValidationTest: cap 3, quantity 5; 409 + code INSUFFICIENT_CAPACITY; nenhuma linha em idempotency_keys. |
| 5 | STK-11 | P1 | PARCIAL | Hot row sob lock_timeout padrão (1s) e 200 concorrentes | Tipo K6. k6: threshold sobre retries_503 (ex.: < 20% das requisições) e registro de p95 de latência. |
| 6 | CAN-09 | P1 | LACUNA | Cancelar e reservar concorrentes (evento esgotado) | Tipo AUTO. Ver STK-09. |
| 7 | CAN-17 | P1 | LACUNA | DELETE em reserva CANCELLED cuja PENDING venceria | Tipo AUTO. ReservationCancelTest: cancelar, expireInPast, expireAll, DELETE; assertar 204, status físico CANCELLED, sem linha EXPIRED, available = cap. |
| 8 | ERR-18 | P1 | LACUNA | Rota inexistente | Tipo AUTO. ApiContractTest: GET em rotas inexistentes; assertar 404 + correlationId; se vier 500, corrigir handler (tratar NoResourceFoundException). |
| 9 | ERR-19 | P1 | LACUNA | Método não permitido | Tipo AUTO. ApiContractTest: parametrizado com as 4 combinações; 405 + Allow + ProblemDetail. |
| 10 | ERR-20 | P1 | LACUNA | Media type não suportado | Tipo AUTO. Ver EVT-20 e RSV-10 (mesma classe). |
| 11 | ERR-22 | P1 | LACUNA | 500 real sem vazar stack (erro vindo do banco) | Tipo AUTO. Reaproveitar EVT-17; assertar corpo sem "postgres", "SQL", "at " e com code INTERNAL_ERROR se o caso continuar 500. |
| 12 | ERR-26 | P1 | LACUNA | X-Correlation-Id em branco ou com mais de 64 caracteres é descartado | Tipo AUTO. ApiContractTest: três chamadas; header da resposta != enviado e tem 36 chars (ver HST-11). |
| 13 | EVT-05 | P1 | LACUNA | capacity decimal (50.5) | Tipo AUTO. EventValidationTest (novo, @SpringBootTest): POST com capacity 50.5; assertar o status decidido (400 INVALID_CAPACITY, ou 201 com capacity=50 e available=50). |
| 14 | EVT-15 | P1 | LACUNA | name com unicode/acentos/emoji | Tipo AUTO. EventValidationTest: POST com "Festival São João 🎉" e GET em seguida; assertEquals no name; Content-Type application/json. |
| 15 | EVT-17 | P1 | LACUNA | name com caractere NUL (\u0000) | Tipo AUTO. EventValidationTest: POST com \u0000 no name; assertar que NÃO é 500 (400 INVALID_EVENT_NAME) e que nada foi inserido. Se for 500, tratar como bug. |
| 16 | EVT-18 | P1 | LACUNA | Corpo vazio | Tipo AUTO. ApiContractTest (novo): POST sem corpo; 400 MALFORMED_REQUEST + correlationId. |
| 17 | EVT-20 | P1 | LACUNA | Content-Type errado (text/plain) ou ausente | Tipo AUTO. ApiContractTest: POST com text/plain; assertar 415 + code. Se vier 500, corrigir o handler. |
| 18 | EVT-21 | P1 | LACUNA | Campos extras / tentativa de mass assignment | Tipo AUTO. EventValidationTest: POST com extras; assertar available=5 e id/createdAt diferentes dos enviados. |
| 19 | EXP-11 | P1 | LACUNA | Exceção no ciclo do job não derruba o scheduler | Tipo AUTO. ExpirationJobResilienceTest (novo): @MockBean IReservationExpirationService que lança na 1ª chamada e retorna normalmente depois; Awaitility: >= 2 invocações; nenhuma exceção propagada. Complemento: 503 (lock) real com DbLock.lockReservation em reserva vencida (SKIP LOCKED pula; ciclo seguinte expira). |
| 20 | EXP-12 | P1 | LACUNA | Reinício da aplicação com vencidas pendentes | Tipo AUTO. MultiInstanceConcurrencyTest (ou novo): semear vencidas antes de startPair(true); Awaitility até 0 PENDING vencidas. Roteiro manual M-06. |
| 21 | EXP-13 | P1 | LACUNA | TTL configurável | Tipo AUTO. Classe com @TestPropertySource(ttl=2s, job ligado, delay=200ms): POST; Awaitility até status físico EXPIRED; available = cap; GET = EXPIRED. |
| 22 | EXP-14 | P1 | LACUNA | Expiração ponta a ponta com TTL real (sem manipular o banco) | Tipo MANUAL. Roteiro M-10. |
| 23 | EXP-18 | P1 | LACUNA | Métrica reservations.expired incrementa pelo tamanho do lote após o commit | Tipo AUTO. Ver OBS-04. |
| 24 | HST-11 | P1 | LACUNA | X-Correlation-Id com 64 caracteres é aceito; com 65 é substituído | Tipo AUTO. ApiContractTest: POST reserva com X-Correlation-Id de 64 e de 65 chars; header da resposta e correlation_id do histórico; nenhum 500. |
| 25 | IDP-09 | P1 | LACUNA | Replay APÓS o cancelamento da reserva original | Tipo AUTO. IdempotencyLifecycleTest (novo): reservar, cancelar, repetir o POST; assertar 201 + corpo igual ao primeiro + replayed + count(reservations)=1 + available inalterado + invariante. GET da reserva mostra CANCELLED (divergência conhecida). |
| 26 | IDP-10 | P1 | LACUNA | Replay APÓS a expiração da reserva original | Tipo AUTO. IdempotencyLifecycleTest: expireInPast + IReservationExpirationService.expireAll; mesmos asserts do IDP-09. |
| 27 | IDP-11 | P1 | LACUNA | Canonicalização do corpo (espaços, ordem, campos extras, 2.0 vs 2) | Tipo AUTO. IdempotencyLifecycleTest: variantes de corpo com a mesma chave; todas replay (201 + Idempotent-Replayed). Incluir quantity 2.0 se o Jackson coagir (D2). |
| 28 | IDP-12 | P1 | LACUNA | UUID do evento em maiúsculas gera o mesmo hash | Tipo AUTO. IdempotencyLifecycleTest: 2ª chamada com eventId.toString().toUpperCase(); 201 replayed, 1 reserva. |
| 29 | IDP-14 | P1 | LACUNA | Replay concorrente quando a 1ª tentativa falha por falta de estoque | Tipo AUTO. IdempotencyLifecycleTest: cap 1 esgotada, 20 POST com a mesma chave; statuses = 409; count(idempotency_keys da chave)=0. |
| 30 | INF-02 | P1 | LACUNA | Restart do PostgreSQL com as APIs no ar | Tipo MANUAL. Roteiro M-03. |
| 31 | INF-06 | P1 | LACUNA | Graceful shutdown com requisições em voo | Tipo MANUAL. Roteiro M-04. |
| 32 | INF-07 | P1 | LACUNA | Healthchecks do Compose | Tipo MANUAL. Roteiro M-01. |
| 33 | INF-09 | P1 | LACUNA | Ordem de dependência | Tipo MANUAL. Roteiro M-01. |
| 34 | INF-10 | P1 | LACUNA | nginx repassa X-Correlation-Id do cliente e gera um quando ausente | Tipo MANUAL. Roteiro M-05. |
| 35 | INF-12 | P1 | LACUNA | nginx não reenvia POST para outra instância | Tipo MANUAL. Roteiro M-02 (passo final). |
| 36 | INF-13 | P1 | LACUNA | Container recriado com IP novo (nginx resolve upstream só na partida) | Tipo MANUAL. Roteiro M-07. |
| 37 | INF-15 | P1 | LACUNA | ./mvnw verify passa do zero em ambiente limpo | Tipo MANUAL. Rodar em máquina limpa ou adicionar workflow de CI com Docker (Testcontainers). |
| 38 | INF-20 | P1 | LACUNA | Falha do banco durante o ciclo do job não derruba a aplicação | Tipo AUTO. Ver EXP-11. |
| 39 | MUL-05 | P1 | LACUNA | Cache divergente entre instâncias | Tipo AUTO. MultiInstanceConcurrencyTest: variante com cache.enabled=true e ttl=2s nas duas JVMs; assertar divergência temporária e convergência (Awaitility). |
| 40 | MUL-06 | P1 | LACUNA | Leitura/cancelamento em instância diferente da criação | Tipo AUTO. Ver QRY-11. |
| 41 | OBS-01 | P1 | LACUNA | reservations.created incrementa só após o commit | Tipo AUTO. MetricsTest (novo): MeterRegistry.counter antes/depois; 1 POST => +1; replay => +0. |
| 42 | OBS-02 | P1 | LACUNA | reservations.created não incrementa em rollback | Tipo AUTO. MetricsTest: POST sem estoque e em evento inexistente; delta 0. |
| 43 | OBS-03 | P1 | LACUNA | reservations.cancelled incrementa só no cancelamento efetivo | Tipo AUTO. MetricsTest: três DELETEs; deltas 1/0/0. |
| 44 | OBS-04 | P1 | LACUNA | reservations.expired incrementa pelo tamanho do lote, após o commit | Tipo AUTO. MetricsTest: seedExpired(N) + expireAll; delta N. |
| 45 | OBS-05 | P1 | LACUNA | reservations.rejected{reason=insufficient_capacity} | Tipo AUTO. MetricsTest: delta 1. |
| 46 | OBS-06 | P1 | LACUNA | reservations.rejected{reason=idempotency_conflict} | Tipo AUTO. MetricsTest: delta 1. |
| 47 | OBS-07 | P1 | LACUNA | reservations.rejected{reason=invalid_state} | Tipo AUTO. MetricsTest: delta 1. |
| 48 | OBS-09 | P1 | LACUNA | /actuator/health responde 200 UP com o banco no ar e 503 DOWN com o banco fora | Tipo MANUAL. Automatizar o 200 em ActuatorOpenApiTest (novo); o DOWN fica no roteiro M-03. |
| 49 | OBS-10 | P1 | LACUNA | Somente health, info e metrics expostos | Tipo AUTO. ActuatorOpenApiTest: parametrizado com os endpoints sensíveis; 404 (ou ≠ 200). |
| 50 | OBS-12 | P1 | LACUNA | Swagger UI acessível | Tipo AUTO. ActuatorOpenApiTest: GET /swagger-ui.html seguindo redirect; 200 text/html. |
| 51 | OBS-13 | P1 | LACUNA | OpenAPI coerente com o contrato real | Tipo AUTO. ActuatorOpenApiTest: parsear o JSON; assertar paths, métodos, parâmetro Idempotency-Key (header, required) e códigos de resposta por operação. Conferir manualmente que DELETE documenta 204/400/404/409. |
| 52 | QRY-07 | P1 | LACUNA | Timestamps coerentes: expiresAt = createdAt + TTL, createdAt <= expiresAt | Tipo AUTO. Ver RSV-25; no GET, comparar também com o corpo do POST. |
| 53 | QRY-11 | P1 | LACUNA | Leitura de reserva criada em outra instância | Tipo AUTO. MultiInstanceConcurrencyTest: POST na instância 0, GET na 1; corpo igual; DELETE na 1 e GET na 0 mostra CANCELLED. |
| 54 | RSV-05 | P1 | LACUNA | quantity decimal (2.5) | Tipo AUTO. ReservationValidationTest (novo): POST 2.5; assertar a decisão. Se 201, assertar quantity=2 e que {"quantity":2} com a mesma chave vira replay. |
| 55 | RSV-06 | P1 | LACUNA | quantity string não numérica ("abc"), booleano, array | Tipo AUTO. ReservationValidationTest: parametrizado com "abc", true, [], {}; 400 MALFORMED_REQUEST (ou INVALID_QUANTITY se decidido). |
| 56 | RSV-08 | P1 | LACUNA | Corpo vazio ou literal null | Tipo AUTO. ApiContractTest: dois POSTs (vazio e "null") com Idempotency-Key válida; 400 MALFORMED_REQUEST. |
| 57 | RSV-10 | P1 | LACUNA | Content-Type errado | Tipo AUTO. ApiContractTest: POST com text/plain e Idempotency-Key; assertar 415 + code. |
| 58 | RSV-16 | P1 | LACUNA | Idempotency-Key com caracteres especiais ASCII (: / # % " ' ; espaço interno) | Tipo AUTO. ReservationValidationTest: parametrizado com várias chaves especiais; 201, replay 201 com Idempotent-Replayed, linha em idempotency_keys com a chave exata. |
| 59 | RSV-25 | P1 | LACUNA | Timestamps coerentes na criação (expiresAt = createdAt + TTL) | Tipo AUTO. ReservationValidationTest: Duration.between(createdAt, expiresAt) == 10 min; createdAt próximo de now(). |
| 60 | SEC-01 | P1 | LACUNA | Corpo gigante (5 MB) em POST /events | Tipo AUTO. SecurityRobustnessTest (novo): POST com name de 5 MB; assertar 4xx e que a aplicação segue respondendo (GET seguinte 200). Via nginx: roteiro M-08. |
| 61 | SEC-02 | P1 | LACUNA | JSON profundamente aninhado (5.000 níveis) | Tipo AUTO. SecurityRobustnessTest: corpo com 5000 "[" ; assertar 400 MALFORMED_REQUEST. |
| 62 | SEC-03 | P1 | LACUNA | Idempotency-Key enorme (16 KB) | Tipo MANUAL. Roteiro M-09 (curl com chave de 16 KB); não cabe em TestRestTemplate de forma confiável. |
| 63 | SEC-04 | P1 | LACUNA | SQL injection no name | Tipo AUTO. SecurityRobustnessTest: POST com payload; GET devolve a mesma string; count(events) > 0; to_regclass(reservations) não nulo. |
| 64 | SEC-05 | P1 | LACUNA | SQL injection no path | Tipo AUTO. SecurityRobustnessTest: GET/DELETE com payload codificado; 400 INVALID_ID_FORMAT. |
| 65 | SEC-06 | P1 | LACUNA | SQL injection na Idempotency-Key | Tipo AUTO. SecurityRobustnessTest: POST com payload; count(idempotency_keys WHERE key = payload) = 1. |
| 66 | SEC-07 | P1 | LACUNA | UUID em maiúsculas no path | Tipo AUTO. SecurityRobustnessTest: GET e POST com toUpperCase(); comparar corpo e Location. |
| 67 | SEC-12 | P1 | LACUNA | Endpoints sensíveis do Actuator | Tipo AUTO. Ver OBS-10. |
| 68 | STK-09 | P1 | LACUNA | Reservar e cancelar concorrentes no mesmo evento esgotado | Tipo AUTO. ReservationConcurrencyTest#reserveAndCancelRace: cap 20 esgotada, 20 cancelamentos + 40 reservas em paralelo; statuses em {201,204,409}; StockInvariant.assertHolds; soma(PENDING) <= 20. |
| 69 | ERR-17 | P2 | PARCIAL | Campos do ProblemDetail (type, title, status, detail, instance, code, correlationId) | Tipo AUTO. ApiContractTest: assertar type "about:blank" e title = reason phrase em um erro real. |
| 70 | ERR-27 | P2 | PARCIAL | X-Instance-Id em todas as respostas (sucesso e erro) | Tipo AUTO. ApiContractTest: assertar X-Instance-Id em 201, 204, 400, 404, 409. |
| 71 | EVT-06 | P2 | PARCIAL | capacity como string numérica ("50") e não numérica ("abc") | Tipo AUTO. EventValidationTest: adicionar o caso "50" e assertar a decisão tomada. |
| 72 | EVT-24 | P2 | PARCIAL | createdAt em ISO-8601 UTC | Tipo AUTO. EventValidationTest: Instant.parse(createdAt) sem erro e dentro de ±1 min do horário do teste. |
| 73 | EVT-29 | P2 | PARCIAL | Cache real: GET após reserva pela mesma instância | Tipo AUTO. EventCacheTest: variante com reserva via API e TTL padrão (1s); assertar defasagem e convergência. |
| 74 | EVT-30 | P2 | PARCIAL | Cache desabilitado lê direto do banco | Tipo AUTO. Teste pequeno com cache.enabled=false: UPDATE + GET imediato devolve o valor novo. |
| 75 | EXP-19 | P2 | PARCIAL | Limite de 20 iterações por ciclo (2.000 por ciclo com batch 100) | Tipo AUTO. Teste do job com backlog > 2.000 e Awaitility (ou documentar o limite como decisão, D14). |
| 76 | MUL-09 | P2 | PARCIAL | Dois jobs, uma reserva travada por DELETE em outra instância | Tipo AUTO. ReservationExpirationTest: ver EXP-17. |
| 77 | QRY-09 | P2 | PARCIAL | GET lê direto do banco (sem cache) | Tipo AUTO. Teste de arquitetura simples: IReservationService.getById não possui @Cacheable (reflexão). |
| 78 | ERR-16 | P2 | LACUNA | Content-Type da resposta de erro é application/problem+json | Tipo AUTO. ApiContractTest: em um 400 e um 404, assertar MediaType.APPLICATION_PROBLEM_JSON. |
| 79 | ERR-21 | P2 | LACUNA | Accept não atendido | Tipo AUTO. ApiContractTest: Accept application/xml; assertar o que for decidido. |
| 80 | ERR-23 | P2 | LACUNA | 503 em leituras (GET) com pool esgotado | Tipo AUTO. DatabasePoolExhaustedTest: acrescentar GETs ao teste existente. |
| 81 | ERR-28 | P2 | LACUNA | Erros de validação com vários campos inválidos | Tipo AUTO. EventValidationTest: dois campos inválidos; assertar que o code é INVALID_EVENT_NAME ou INVALID_CAPACITY de forma estável (ou documentar que não é). |
| 82 | EVT-07 | P2 | LACUNA | capacity acima de int (3000000000) | Tipo AUTO. EventValidationTest: POST com 3000000000; assertar 400 e o code decidido. |
| 83 | EVT-08 | P2 | LACUNA | name com 1 caractere | Tipo AUTO. EventValidationTest: POST name="A"; 201 e name devolvido igual. |
| 84 | EVT-12 | P2 | LACUNA | name vazio ("") | Tipo AUTO. EventValidationTest: POST name=""; 400 INVALID_EVENT_NAME (mesma constraint @NotBlank do caso de espaços). |
| 85 | EVT-14 | P2 | LACUNA | name com espaços nas bordas (trim) e limite de 150 | Tipo AUTO. EventValidationTest: POST "  abc  " => 201 com name="abc"; POST 150 úteis + 2 espaços => assertar a decisão. |
| 86 | EVT-16 | P2 | LACUNA | name no limite em emoji (unidades UTF-16 vs pontos de código) | Tipo AUTO. EventValidationTest: POST com 75, 76 e 150 emojis; assertar a decisão. |
| 87 | EVT-22 | P2 | LACUNA | Eventos com o mesmo nome | Tipo AUTO. EventValidationTest: dois POSTs iguais; ids distintos. |
| 88 | EVT-23 | P2 | LACUNA | Criação concorrente de eventos | Tipo AUTO. EventValidationTest: 50 POSTs em paralelo; count distinct ids = 50. |
| 89 | EXP-15 | P2 | LACUNA | Job não toca reservas CANCELLED/EXPIRED | Tipo AUTO. ReservationExpirationTest: cancelar, expireInPast, expireAll; assertar ausência de EXPIRED no histórico e invariante. |
| 90 | EXP-16 | P2 | LACUNA | Estoque só volta após o job | Tipo AUTO. ReservationExpirationTest: cap 1; vencer; POST => 409; expireAll; POST => 201. |
| 91 | EXP-17 | P2 | LACUNA | Reserva travada por DELETE em andamento é ignorada (SKIP LOCKED) e expirada no ciclo seguinte | Tipo AUTO. ReservationExpirationTest: DbLock.lockReservation numa reserva vencida; assertar expireBatch()==0 e depois ==1. |
| 92 | HST-09 | P2 | LACUNA | Histórico é append-only: o código nunca faz UPDATE/DELETE em reservation_history | Tipo AUTO. AppendOnlyHistoryTest: grep em src/main/java por "UPDATE reservation_history" e "DELETE FROM reservation_history" (zero ocorrências) ou trigger de teste que lança em UPDATE/DELETE e suíte completa passa. |
| 93 | HST-12 | P2 | LACUNA | Histórico do evento de job com instância correta no Compose | Tipo MANUAL. Roteiro M-10 (consulta psql ao final). |
| 94 | IDP-13 | P2 | LACUNA | Chave diferencia maiúsculas de minúsculas ("Abc" != "abc") | Tipo AUTO. IdempotencyLifecycleTest: duas chaves com caixa diferente; 2 reservas. |
| 95 | IDP-15 | P2 | LACUNA | Replay concorrente com liberação de estoque no meio | Tipo AUTO. IdempotencyLifecycleTest: stress leve; count(reservations criadas pela chave) <= 1 e invariante. |
| 96 | IDP-18 | P2 | LACUNA | Conflito não altera a chave original | Tipo AUTO. IdempotencyLifecycleTest: sequência acima; assertar 201 replayed com o mesmo id. |
| 97 | IDP-22 | P2 | LACUNA | Crescimento sem limite da tabela idempotency_keys | Tipo MANUAL. Decisão de produto (D4). Se adotado TTL: teste de job de limpeza apagando chaves antigas sem afetar replays recentes. |
| 98 | INF-03 | P2 | LACUNA | Postgres indisponível na subida das APIs | Tipo MANUAL. Roteiro M-01 (passo de variação). |
| 99 | INF-14 | P2 | LACUNA | Corpo acima de 1 MB via nginx | Tipo MANUAL. Roteiro M-08. |
| 100 | INF-16 | P2 | LACUNA | Imagem roda como usuário não root; build sem testes | Tipo MANUAL. Roteiro M-01 (passo extra). |
| 101 | MUL-10 | P2 | LACUNA | Relógio do banco como fonte única de tempo | Tipo AUTO. Teste de arquitetura: grep em src/main/java por "Instant.now\|LocalDateTime.now\|OffsetDateTime.now\|System.currentTimeMillis" (zero ocorrências fora de logs). |
| 102 | OBS-11 | P2 | LACUNA | /actuator/metrics/reservations.* acessível | Tipo AUTO. ActuatorOpenApiTest: GET após um POST; 200 e COUNT >= 1. |
| 103 | OBS-14 | P2 | LACUNA | Logs com correlationId em todas as linhas de uma requisição | Tipo MANUAL. Roteiro M-05. |
| 104 | OBS-15 | P2 | LACUNA | Logs do job com correlation id job-xxxxxxxx | Tipo MANUAL. Roteiro M-10. |
| 105 | QRY-08 | P2 | LACUNA | Fronteira exata do vencimento (expires_at == agora) | Tipo AUTO. ReservationApiTest: UPDATE expires_at = NOW() dentro de uma tx aberta e GET após 10 ms; assertar EXPIRED. (Fronteira com DELETE já coberta em ReservationExpirationConcurrencyTest#i9_cancelRacingExactlyAtTheExpiryBoundary.) |
| 106 | RSV-07 | P2 | LACUNA | quantity string numérica ("2") e acima de int (99999999999) | Tipo AUTO. ReservationValidationTest: assertar status/code decididos para ambos. |
| 107 | RSV-11 | P2 | LACUNA | Campos extras no corpo | Tipo AUTO. ReservationValidationTest: POST com extras; eventId da resposta = o do path. |
| 108 | RSV-14 | P2 | LACUNA | Idempotency-Key com 1 caractere | Tipo AUTO. ReservationValidationTest: POST com chave "a"; 201; linha em idempotency_keys. |
| 109 | RSV-17 | P2 | LACUNA | Idempotency-Key com unicode (bytes UTF-8 no header) | Tipo MANUAL. Roteiro curl com header em UTF-8; conferir 201 e replay; repetir com 100 acentos -> 400. |
| 110 | RSV-18 | P2 | LACUNA | Idempotency-Key com espaços nas bordas ("  abc  ") | Tipo AUTO. ReservationValidationTest: POST com "  abc  " e depois "abc"; assertar replay (ou chaves distintas) conforme observado. |
| 111 | RSV-24 | P2 | LACUNA | Precedência das validações | Tipo AUTO. ReservationValidationTest: três chamadas; fixar a ordem como contrato. |
| 112 | SEC-08 | P2 | LACUNA | UUIDs em formatos exóticos | Tipo AUTO. SecurityRobustnessTest: três formatos; assertar o que for observado e registrar. |
| 113 | SEC-09 | P2 | LACUNA | Caracteres de controle no name (\u0001, \n, \t) | Tipo AUTO. SecurityRobustnessTest: parametrizado; assertar != 500 e round-trip igual quando 201. |
| 114 | SEC-10 | P2 | LACUNA | HTML/script no name | Tipo AUTO. SecurityRobustnessTest: POST e GET; Content-Type JSON; corpo contém a string literal. |
| 115 | SEC-11 | P2 | LACUNA | X-Correlation-Id com caracteres estranhos | Tipo MANUAL. Roteiro M-05 (passo opcional). |
| 116 | SEC-13 | P2 | LACUNA | Path traversal em ids | Tipo AUTO. SecurityRobustnessTest: duas variantes codificadas. |
| 117 | SEC-14 | P2 | LACUNA | Sem autenticação (fora do escopo): qualquer cliente pode cancelar/consultar qualquer reserva e reutilizar chaves alheias | Tipo MANUAL. Decisão documentada no README (limitação); sem teste. |
| 118 | STK-06 | P2 | LACUNA | Quantities mistas que fecham EXATAMENTE a capacity | Tipo AUTO. ReservationConcurrencyTest: cap 10, 8 tentativas quantity 3 e 8 quantity 2; assertar available >= 0, statuses {201,409} e invariante (total vendido depende da ordem). |
| 119 | STK-12 | P2 | LACUNA | Capacity 1.000.000 com várias reservas | Tipo AUTO. ReservationConcurrencyTest: cap 1_000_000, 100 POSTs quantity 10; available = 999000. |

## 6. Comportamentos observados no código que merecem decisão

Só constam itens que o código realmente mostra. Itens marcados "(verificar)" dependem de comportamento padrão de Spring/Jackson/Tomcat/nginx/PostgreSQL que não está coberto por teste: a leitura do código aponta o desfecho, mas ele deve ser confirmado por um teste ou pelo roteiro manual antes de se tratar como bug.

| # | Tema | Onde | O que o código mostra | Por que importa | Sugestão |
|---|---|---|---|---|---|
| D1 | `@ExceptionHandler(Exception.class)` engole erros HTTP do framework (404 de rota, 405, 415, 406) (verificar) | `GlobalExceptionHandler.java:111-120` | `handleAny` captura `Exception` e devolve 500 `INTERNAL_ERROR`, além de logar `log.error` com stack. Não há handler específico nem herança de `ResponseEntityExceptionHandler` para `NoResourceFoundException`, `HttpRequestMethodNotSupportedException`, `HttpMediaTypeNotSupportedException`, `HttpMediaTypeNotAcceptableException`. O resolvedor do `@ControllerAdvice` tem precedência sobre o `DefaultHandlerExceptionResolver` | Rota inexistente, método errado e Content-Type errado são erros do cliente (404/405/415) e provavelmente viram 500; polui alertas de 5xx e o log com stack a cada sonda. Contraria "Tratamento explícito de erros" (RNF) | Confirmar com ERR-18/19/20; tratar essas exceções retornando o status do `ErrorResponse` (ex.: handler para `ErrorResponseException`/`ServletException`) com `code` e `correlationId` |
| D2 | Coerção permissiva de números pelo Jackson | `CreateReservationRequest.java:6-8`, `CreateEventRequest.java:14-15`, `ReservationService.java:76-81` | `quantity` e `capacity` são `Integer`. Com os padrões do Spring Boot (`ACCEPT_FLOAT_AS_INT` e coerção de strings numéricas ligadas), 2.5 vira 2 e "2" vira 2; valores acima de int viram `MALFORMED_REQUEST` em vez de `INVALID_QUANTITY`/`INVALID_CAPACITY` (verificar) | O contrato (PLANO 4) diz "entre 1 e max". Reservar 2.9 e receber 2 ingressos é surpreendente e o hash da idempotência passa a tratar 2, 2.0 e 2.9 como o mesmo request | Decidir: rejeitar decimais (`DeserializationFeature.ACCEPT_FLOAT_AS_INT` desligado + `MapperFeature.ALLOW_COERCION_OF_SCALARS` desligado) ou documentar. Cobrir em EVT-05..07, RSV-05..07, IDP-11 |
| D3 | Replay devolve a foto da criação, não o estado atual | `ReservationService.java:100-103, 116, 135-143`; `IdempotencyService.java:41-55` | O replay devolve 201 com o corpo salvo (status `PENDING` congelado e `expiresAt` original) sem consultar a reserva. Após cancelamento ou expiração da reserva original, repetir o POST com a mesma chave devolve 201 `PENDING`, `Idempotent-Replayed: true`, com `Location` de uma reserva que o `GET` mostra como `CANCELLED`/`EXPIRED`; não cria nova reserva nem devolve/baixa estoque | É a semântica usual (resposta original), mas o cliente pode acreditar que tem uma reserva ativa. Não está documentado no README nem coberto por teste | Documentar no README/Swagger e fixar em IDP-09/IDP-10; alternativa: replay refletir o status efetivo atual |
| D4 | Chave de idempotência global e sem TTL | `IdempotencyRepository.java:23-30`, `V3__create_idempotency_keys.sql:2`, README seção 11 | A PK é só `idempotency_key`: não há escopo por cliente, por evento ou por rota, e a tabela nunca é limpa. Uma chave igual usada por outro cliente com o mesmo evento e quantidade recebe o replay da reserva de OUTRO cliente (retornando o `id` dela) | Sem autenticação no escopo, não há como separar clientes, mas é um vazamento de dados entre clientes por colisão de chaves curtas e crescimento ilimitado da tabela. O README já reconhece a ausência de TTL | Registrar como limitação; se possível, escopar por (chave, rota) e criar rotina de limpeza por `created_at` |
| D5 | Validação de `name` antes do `trim`, contagem em UTF-16 e NUL | `CreateEventRequest.java:13`, `EventService.java:28`, `V1__create_events.sql:3` | `@Size(max=150)` valida o texto bruto; o serviço grava `name.trim()`. `@Size` conta unidades UTF-16 (emoji = 2) enquanto `VARCHAR(150)` conta pontos de código. O caractere NUL (`\u0000`) passa em `@NotBlank` e é rejeitado pelo PostgreSQL (verificar): o desfecho provável é 500 | Nome válido de 150 caracteres úteis com espaços nas bordas é rejeitado; nome com NUL pode gerar 500 (falha de entrada vira erro de servidor) | Validar pós-trim e rejeitar caracteres de controle/NUL com `INVALID_EVENT_NAME`; decidir contagem por code points. Cobrir EVT-14..17 |
| D6 | `INSTANCE_ID` com mais de 30 caracteres quebra todas as escritas | `InstanceIdFilter.java:24`, `AuditContext.java:15`, `V4__create_reservation_history.sql:11` | `instance_id` é `VARCHAR(30)` e o valor vem de env sem validação. Um `INSTANCE_ID` maior faz todo `INSERT` em `reservation_history` falhar (22001), ou seja, toda criação/cancelamento/expiração vira 500 | Falha de configuração só aparece em runtime e em todas as escritas | Validar no startup (`@Validated`/`@Size`) ou truncar; teste de contexto com valor longo |
| D7 | nginx: upstreams resolvidos só na partida e `proxy_next_upstream off` (verificar) | `docker/nginx/nginx.conf:18-22, 38-39` | `server api1:8080; server api2:8080` são resolvidos na inicialização do nginx (não há `resolver`/variável). Com `proxy_next_upstream off`, nenhuma requisição é reenviada, nem GET, mesmo quando a instância está fora | Recriar um container com IP novo pode deixar o nginx apontando para um endereço morto até um reload; com uma API parada, parte do tráfego vê 502 até o nginx marcar a upstream como falha. Item de resiliência do RNF "múltiplas instâncias" | Roteiros M-02 e M-07; se confirmado, usar `resolver 127.0.0.11` com variável ou `max_fails`/`fail_timeout` explícitos e permitir retry idempotente de GET (`proxy_next_upstream_tries` para GET) |
| D8 | Janela de graceful shutdown maior que o `stop_grace_period` padrão | `application.yml:17` (20s), `docker-compose.yml` (sem `stop_grace_period`) | O Spring espera até 20s por requisições em voo, mas o Compose envia SIGKILL após 10s por padrão | Em cenário de requisição longa o encerramento gracioso de 20s nunca é alcançado. Na prática as requisições duram <3s (statement timeout), então o impacto é baixo | Definir `stop_grace_period: 25s` nas apis ou reduzir o timeout para <10s; Roteiro M-04 |
| D9 | Métrica `reservations.rejected{reason=db_busy}` conta qualquer 503 da API | `GlobalExceptionHandler.java:113-117` | O contador é incrementado no handler global, inclusive para `GET /events/{id}` e `GET /reservations/{id}` (o nome sugere só reservas) | Indicador de saturação correto, mas nome/escopo enganosos no dashboard | Renomear para `http.rejected{reason=db_busy}` ou documentar no README |
| D10 | Documentação desatualizada em relação a testes e logs | `README.md:322`, `PLANO.md` §9 vs `ReservationService.java:122,129` | O README diz que "não há teste de integração que force o timeout", mas `DatabaseBusyTest`, `DatabaseStatementTimeoutTest` e `DatabasePoolExhaustedTest` existem. O PLANO cita o log `reservation created`; o código loga `capacity acquired` e `commit completed` | Afirmação falsa no README derruba credibilidade no code review | Corrigir o README (e o PLANO, se ainda for referência) |
| D11 | Ordem de validação: 400 tem precedência sobre 404 | `ReservationService.java:73-85` | `quantity`/chave são validadas antes de qualquer acesso ao banco; em evento inexistente com `quantity=0` a resposta é 400, não 404. Path inválido > header ausente > corpo, por ordem de resolução dos argumentos do Spring | Comportamento razoável, mas não documentado nem testado (RSV-24) | Documentar a precedência e testar |
| D12 | Erro de validação com vários campos inválidos não é determinístico | `GlobalExceptionHandler.java:54` | `getFieldError()` devolve "o primeiro" erro; a ordem entre `name` e `capacity` não é garantida | Duas chamadas iguais podem devolver códigos diferentes (INVALID_EVENT_NAME vs INVALID_CAPACITY) | Ordenar por nome do campo ou devolver lista de erros |
| D13 | `created_at`/`expires_at` vêm de `NOW()` (início da transação) | `ReservationRepository.java:43` | No PostgreSQL `NOW()` é o instante de início da transação; uma reserva que espera até 1s pela linha quente (ou pela chave) nasce com `expires_at` contado desde o início da transação | Diferença de até `lock_timeout` (1s) no TTL de 10 min: irrelevante, mas vale mencionar ao discutir relógio do banco | Sem ação; citar no README |
| D14 | Teto de 2.000 expirações por ciclo do job | `ReservationExpirationJob.java:18,28-35` | `MAX_ITERATIONS = 20` com batch 100: backlog maior é drenado em vários ciclos (5s cada). O `catch` só pega `Exception` (não `Error`) | Com backlog de 100.000 vencidas o estoque volta em ~4 min; nenhum teste do job usa `MAX_ITERATIONS` | Tornar configurável ou documentar; cobrir EXP-19 |
| D15 | Limite da chave conta `String.length()` e o header é decodificado como ISO-8859-1 (verificar) | `ReservationService.java:38,73` | Chaves com acentos enviadas em UTF-8 são lidas como bytes Latin-1 (cada byte vira 1 caractere) e contam como o dobro | Chave de 100 acentos (200 bytes) é rejeitada por `INVALID_IDEMPOTENCY_KEY` embora tenha 100 caracteres | Documentar "ASCII recomendado" no Swagger; RSV-17 |
| D16 | Não há pipeline de CI no repositório | raiz do projeto (sem `.github/workflows`) | O DoD exige `./mvnw verify` verde, mas nada o executa automaticamente; os testes dependem de Docker | Regressões passam despercebidas e o critério de avaliação "testes automatizados" fica sem evidência reproduzível | Workflow simples com JDK 21 e Docker (INF-15) |
| D17 | Compose sem `restart:` e nginx sem healthcheck | `docker-compose.yml:17-60` | Nenhum serviço tem política de restart; o nginx não tem healthcheck; `depends_on` só vale na subida | Se uma API morrer (OOM, falha de conexão na subida), não volta sozinha; o nginx continua apontando para ela (D7) | `restart: unless-stopped` nas apis; healthcheck do nginx; avaliar `max_fails`/`fail_timeout` |
| D18 | Correlation id do cliente é aceito com qualquer conteúdo até 64 chars | `CorrelationIdFilter.java:29-33` | Só o comprimento é validado; o valor vai ao MDC, aos logs e ao banco | Risco baixo de injeção em logs (o servidor já rejeita CR/LF em header); útil só para higiene | Restringir a `[A-Za-z0-9._-]` (SEC-11) |
| D19 | Métricas são por instância | `ReservationService.java:88-90,128,184`, `ReservationExpirationService.java:127` | `MeterRegistry` local sem tag de instância; via nginx, `/actuator/metrics` alterna entre api1 e api2 | Consultar métricas pelo nginx dá valores inconsistentes; testes/roteiros de métrica precisam acessar uma instância específica | Adicionar `management.metrics.tags.instance=${INSTANCE_ID}` e consultar cada instância |
| D20 | `UUID.fromString` é leniente | `EventController.java:64`, `ReservationController.java:100,122` | Spring converte path variables com `UUID.fromString`, que aceita formatos como `1-1-1-1-1` (vira um UUID válido, resposta 404, não 400 `INVALID_ID_FORMAT`) (verificar) | Pequena inconsistência com o contrato "UUID malformado => 400"; sem risco de segurança | Documentar ou validar com regex estrita (SEC-08) |
| D21 | Falhas de conexão do PostgreSQL em voo não entram em `BUSY_SQL_STATES` (verificar) | `GlobalExceptionHandler.java:38, 122-133` | Só `55P03`, `57014`, `40P01` e as exceções de obtenção de conexão viram 503. Queries em voo durante um restart do Postgres falham com `57P01` (admin_shutdown) ou `08006`/`08003` (conexão), que caem em 500 `INTERNAL_ERROR` | Durante manutenção do banco, o cliente vê 500 (não repetir) em vez de 503 + `Retry-After` (seguro repetir com a chave) | Incluir `57P01`, `08xxx` (classe 08) em `BUSY_SQL_STATES`; roteiro M-03 |

**Correções rápidas de documentação apontadas pela leitura:** `README.md:322` (teste de 503 existe); `PLANO.md` §9 (nomes de log); nenhum dos dois menciona o comportamento D3.
