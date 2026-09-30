# Flash Booking — Plano de Desenvolvimento (final)

> Base: `flash-booking-arquitetura-estudo-v2.md`, com os ajustes de robustez da seção 20.
> Objetivo: uma solução **enxuta, correta sob concorrência e fácil de defender** no code review.
> Princípio: *menos infraestrutura, mais solidez.*

---

## 1. Escopo

### 1.1 Obrigatório (contrato do PDF)

```text
POST   /events
GET    /events/{id}
POST   /events/{id}/reservations
GET    /reservations/{id}
DELETE /reservations/{id}
```

Requisitos não funcionais: múltiplas instâncias, zero oversell, expiração automática, idempotência, consistência eventual na disponibilidade, erros explícitos.
Entregáveis: repositório GitHub, Docker Compose, testes automatizados, README (instruções, decisões, trade-offs, evoluções).

### 1.2 Fora da V1 (só citar no README como evolução)

Kafka, Redis, Outbox, pagamento/estorno, `CONFIRMED`, clientes, locais, meia-entrada, microserviços, saga, rate limiting.

---

## 2. Stack e decisões de tecnologia

| Item | Escolha | Motivo |
|---|---|---|
| Linguagem | Java 21 | records, virtual threads não necessárias |
| Framework | Spring Boot 3.3.x | ProblemDetail nativo |
| Build | Maven (wrapper `mvnw`) | simples e conhecido |
| Banco | PostgreSQL 16 | `SKIP LOCKED`, `UPDATE` atômico, `CHECK` |
| Acesso a dados | **MyBatis (SQL explícito em XML)**, sem JPA; transações do Spring | o SQL crítico é o coração da solução; evita magia do ORM e facilita explicar |
| Migrations | Flyway | lock de migração protege subida simultânea |
| Cache | Caffeine (local por instância) | consistência eventual sem nova infra |
| Testes | JUnit 5 + Testcontainers (Postgres real) | locks e transações reais |
| Carga | k6 (imagem `grafana/k6` via Compose) | concorrência real via nginx |
| Docs | springdoc-openapi (Swagger UI) | demonstração |
| Observabilidade | Actuator + Micrometer + MDC | health, métricas, correlation id |
| Proxy | nginx (round-robin) | prova de múltiplas instâncias |

Padrão de services mantido: `IEventService` + `impl/EventService` (justificativa no README: contrato explícito e test doubles, reconhecendo a cerimônia com uma única implementação).

---

## 3. Arquitetura

```text
             cliente / k6
                  |
                nginx :8080
            /            \
        api1              api2      (cache Caffeine local em cada)
            \            /
             PostgreSQL 16  (fonte da verdade)
```

- Monólito: um único artefato, organizado por camadas.
- Toda regra crítica vive no PostgreSQL; nenhuma decisão depende de memória da instância.
- O cache é usado **só** em `GET /events/{id}`. A escrita nunca consulta cache.

### Estrutura de packages

Organização **por camadas** (padrão mais comum em Java; o projeto tem só dois agregados, então separar por domínio não compensa):

```text
com.flashbooking
├── FlashBookingApplication
├── controller
│   ├── EventController
│   └── ReservationController
├── service
│   ├── IEventService
│   ├── IReservationService
│   ├── IReservationExpirationService
│   ├── IIdempotencyService                 (hash + replay)
│   └── impl
│       ├── EventService
│       ├── ReservationService
│       ├── ReservationExpirationService
│       └── IdempotencyService
├── repository                              (interfaces @Mapper MyBatis; SQL em resources/mapper/*.xml)
│   ├── EventRepository
│   ├── ReservationRepository
│   ├── ReservationHistoryRepository
│   └── IdempotencyRepository
├── model
│   ├── domain                              (records que espelham as tabelas)
│   │   ├── Event
│   │   ├── Reservation
│   │   ├── ReservationHistory
│   │   └── IdempotencyKey
│   ├── dto
│   │   ├── request/{CreateEventRequest, CreateReservationRequest}
│   │   └── response/{EventResponse, ReservationResponse}
│   └── enums
│       ├── ReservationStatus               (PENDING, CANCELLED, EXPIRED)
│       ├── HistoryAction                   (CREATED, CANCELLED, EXPIRED)
│       └── ErrorCode
├── job
│   └── ReservationExpirationJob            (@Scheduled; delega ao service)
├── exception
│   ├── BusinessException
│   └── GlobalExceptionHandler
├── filter
│   ├── CorrelationIdFilter
│   └── InstanceIdFilter
├── config
│   ├── BookingProperties
│   ├── CacheConfig
│   └── OpenApiConfig
└── infra
    └── TxSupport                           (SET LOCAL timeouts)
```

---

## 3.1 Modelo de dados

Idioma único: **inglês** (rotas, JSON, banco, enums, erros).

### V1__create_events.sql

```sql
CREATE TABLE events (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(150) NOT NULL,
    total_capacity INTEGER      NOT NULL,
    available      INTEGER      NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_events_capacity  CHECK (total_capacity > 0),
    CONSTRAINT ck_events_available CHECK (available >= 0 AND available <= total_capacity)
);
```

### V2__create_reservations.sql

```sql
CREATE TABLE reservations (
    id         UUID PRIMARY KEY,                       -- gerado na aplicação
    event_id   UUID        NOT NULL REFERENCES events(id),
    quantity   INTEGER     NOT NULL,
    status     VARCHAR(20) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_reservations_quantity CHECK (quantity > 0),
    CONSTRAINT ck_reservations_status   CHECK (status IN ('PENDING','CANCELLED','EXPIRED'))
);

CREATE INDEX idx_reservations_pending_expiration
    ON reservations (expires_at) WHERE status = 'PENDING';
```

### V3__create_idempotency_keys.sql

```sql
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(150) PRIMARY KEY,
    request_hash    VARCHAR(64)  NOT NULL,
    http_status     INTEGER      NULL,
    response_json   JSONB        NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
```

### V4__create_reservation_history.sql

Tabela **append-only** com todas as mudanças de estado da reserva (criação, cancelamento, expiração). Nunca sofre `UPDATE` nem `DELETE`.

```sql
CREATE TABLE reservation_history (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reservation_id  UUID         NOT NULL REFERENCES reservations(id),
    event_id        UUID         NOT NULL,
    action          VARCHAR(20)  NOT NULL,
    previous_status VARCHAR(20)  NULL,
    new_status      VARCHAR(20)  NOT NULL,
    quantity        INTEGER      NOT NULL,
    reason          VARCHAR(30)  NOT NULL,
    correlation_id  VARCHAR(64)  NULL,
    instance_id     VARCHAR(30)  NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_history_action CHECK (action IN ('CREATED','CANCELLED','EXPIRED')),
    CONSTRAINT ck_history_new_status CHECK (new_status IN ('PENDING','CANCELLED','EXPIRED'))
);

CREATE INDEX idx_reservation_history_reservation
    ON reservation_history (reservation_id, created_at);
```

| action | previous_status | new_status | reason |
|---|---|---|---|
| `CREATED` | `NULL` | `PENDING` | `CLIENT_REQUEST` |
| `CANCELLED` | `PENDING` | `CANCELLED` | `CLIENT_REQUEST` |
| `EXPIRED` | `PENDING` | `EXPIRED` | `TTL_EXPIRED` |

Regras:
- O registro é gravado **na mesma transação** da mudança de estado: se o estado muda, o histórico existe; se há rollback, ele some junto.
- Guarda `correlation_id` e `instance_id`, o que permite rastrear qual requisição e qual instância causou cada transição.
- Só registra transição **efetiva**. Requisições rejeitadas (409, 404, 400) ou repetidas sem efeito (`DELETE` em reserva já cancelada, replay de idempotência) não entram aqui, porque não mudam estado e as rejeitadas fazem rollback. Elas ficam nos logs e nas métricas, com o mesmo `correlationId`.

### Semântica de `available`

`available` = capacidade menos ingressos **retidos por reservas `PENDING`**. Reserva cancelada ou expirada devolve. Invariante verificada nos testes:

```text
total_capacity = available + SUM(quantity das reservas PENDING)
```

---

## 4. Contrato da API

Todas as respostas de erro usam `ProblemDetail` (RFC 7807) com as propriedades extras `code` e `correlationId`.

### POST /events → 201

```json
// request
{ "name": "Java Festival", "capacity": 50 }
// response  (Location: /events/{id})
{ "id": "57d8...", "name": "Java Festival", "capacity": 50, "available": 50, "createdAt": "2026-09-30T14:00:00Z" }
```

Validação: `name` não vazio, ≤150; `capacity` entre 1 e 1.000.000.

### GET /events/{id} → 200

Mesmo modelo. Disponibilidade **pode estar defasada** (cache com TTL curto).

### POST /events/{id}/reservations → 201

Header obrigatório `Idempotency-Key` (1–150 caracteres).

```json
// request
{ "quantity": 2 }
// response  (Location: /reservations/{id})
{ "id": "98ac...", "eventId": "57d8...", "quantity": 2, "status": "PENDING",
  "expiresAt": "2026-09-30T14:10:00Z", "createdAt": "2026-09-30T14:00:00Z" }
```

- `quantity` entre 1 e `booking.reservation.max-quantity` (padrão 10).
- Repetição com a mesma chave e o mesmo request → **mesma resposta** (mesmo status e corpo) + header `Idempotent-Replayed: true`.

### GET /reservations/{id} → 200

Mesmo modelo da reserva. **Status efetivo:** se `PENDING` e `expires_at <= NOW()` (relógio do banco), responde `EXPIRED`, sem efeito colateral. O job faz a alteração física e a devolução.

### DELETE /reservations/{id}

| Situação | Resposta |
|---|---|
| `PENDING` e não vencida → cancela e devolve estoque | 204 |
| já `CANCELLED` | 204 (não devolve de novo) |
| `EXPIRED` (ou `PENDING` já vencida) | 409 `INVALID_RESERVATION_STATE` |
| não existe | 404 `RESERVATION_NOT_FOUND` |

### Headers padronizados

| Header | Uso |
|---|---|
| `X-Correlation-Id` | aceito na entrada ou gerado; devolvido; vai para o MDC e para o erro |
| `X-Instance-Id` | resposta: identifica `api1`/`api2` (prova de balanceamento) |
| `Idempotent-Replayed` | `true` em replay |
| `Retry-After: 1` | nos 503 |
| `Location` | nos 201 |

### Catálogo de erros

| HTTP | code | Quando |
|---|---|---|
| 400 | `MISSING_IDEMPOTENCY_KEY` | header ausente |
| 400 | `INVALID_IDEMPOTENCY_KEY` | vazio ou >150 caracteres |
| 400 | `INVALID_QUANTITY` | fora de 1..max |
| 400 | `INVALID_CAPACITY` | fora de 1..1.000.000 |
| 400 | `INVALID_EVENT_NAME` | vazio ou >150 |
| 400 | `INVALID_ID_FORMAT` | UUID malformado |
| 400 | `MALFORMED_REQUEST` | JSON inválido |
| 404 | `EVENT_NOT_FOUND` | evento inexistente |
| 404 | `RESERVATION_NOT_FOUND` | reserva inexistente |
| 409 | `INSUFFICIENT_CAPACITY` | sem estoque |
| 409 | `INVALID_RESERVATION_STATE` | cancelar reserva expirada |
| 409 | `IDEMPOTENCY_KEY_CONFLICT` | mesma chave, request diferente |
| 503 | `DATABASE_BUSY` | timeout de lock/statement, pool esgotado, deadlock |
| 500 | `INTERNAL_ERROR` | qualquer outro erro (sem stack trace na resposta) |

---

## 5. Fluxos críticos (o coração da solução)

Todas as transações rodam em `READ COMMITTED`. Toda transação de escrita começa com:

```sql
SET LOCAL lock_timeout = '1s';
SET LOCAL statement_timeout = '3s';
```

(implementado em `TxSupport`, chamado no início de cada `@Transactional`; `SET LOCAL` só vale dentro da transação.)

### 5.1 Criar reserva

```text
0. (fora da tx) validar header e body; calcular request_hash
BEGIN + SET LOCAL
1. INSERT idempotency_keys (key, hash) ON CONFLICT DO NOTHING
     0 linhas  -> outra tx tem/tinha a chave:
                  (o INSERT espera a tx concorrente terminar)
                  SELECT da chave:
                    hash diferente -> 409 IDEMPOTENCY_KEY_CONFLICT
                    hash igual     -> ROLLBACK e devolver resposta salva (Idempotent-Replayed: true)
     1 linha   -> segue (venceu a disputa)
2. gerar UUID da reserva na aplicação
3. INSERT reservations (PENDING, expires_at = NOW() + TTL) RETURNING created_at, expires_at
     violação de FK (23503) -> 404 EVENT_NOT_FOUND
4. montar a resposta a partir do RETURNING (timestamps do banco)
5. INSERT reservation_history (CREATED, previous_status NULL, new_status PENDING, correlation_id, instance_id)
6. UPDATE idempotency_keys SET http_status = 201, response_json = :body
7. UPDATE events SET available = available - :q WHERE id = :id AND available >= :q   <-- hot row, por último
     0 linhas -> (o evento existe, pois a FK do passo 3 passou) 409 INSUFFICIENT_CAPACITY -> ROLLBACK
8. COMMIT  -> HTTP 201
```

Pontos de defesa:
- O `UPDATE` do estoque é a **última** operação antes do `COMMIT`: o lock da linha quente é segurado pelo menor tempo possível.
- O `UNIQUE` da chave resolve a disputa entre instâncias: o segundo insert **espera** o primeiro terminar (não existe estado `PROCESSING`).
- Erro de negócio → rollback → a chave **não é retida**. Retry com a mesma chave reexecuta (comportamento documentado).
- Como não há `DELETE` de eventos, `0 linhas` no passo 6 só pode significar falta de estoque (dispensa a consulta extra).
- Nunca `SELECT` da disponibilidade seguido de `UPDATE` (race condition).

### 5.2 Hash da idempotência

`SHA-256( "POST" + "|" + "/events/{eventId em minúsculas}/reservations" + "|" + JSON canônico do body )`, em hex (64 chars).
Mesma chave em outro evento ou com outra quantidade → `IDEMPOTENCY_KEY_CONFLICT`.

### 5.3 Cancelar reserva

```sql
-- BEGIN + SET LOCAL
UPDATE reservations
   SET status = 'CANCELLED', updated_at = NOW()
 WHERE id = :id AND status = 'PENDING' AND expires_at > NOW()
RETURNING event_id, quantity;
```

- 1 linha → `INSERT reservation_history` (`CANCELLED`, `PENDING → CANCELLED`, `CLIENT_REQUEST`) + `UPDATE events SET available = available + :quantity WHERE id = :event_id` → COMMIT → 204.
- 0 linhas → `SELECT status` (fora do caminho quente):
  - não existe → 404
  - `CANCELLED` → 204 (sem devolver)
  - `EXPIRED` ou `PENDING` vencida → 409 `INVALID_RESERVATION_STATE`

Só quem consegue a transição condicional devolve estoque. Se o job segura o lock da linha, o `UPDATE` espera, reavalia `status` e cai em 0 linhas.

### 5.4 Expiração (job)

`@Scheduled(fixedDelayString = "${booking.reservation.expiration-job-delay}")` em **todas** as instâncias. Uma transação por lote:

```sql
SELECT id, event_id, quantity
  FROM reservations
 WHERE status = 'PENDING' AND expires_at <= NOW()
 ORDER BY expires_at
 LIMIT :batchSize
 FOR UPDATE SKIP LOCKED;

UPDATE reservations SET status = 'EXPIRED', updated_at = NOW() WHERE id = ANY(:ids);
```

Inserir em `reservation_history` uma linha por reserva expirada (`EXPIRED`, `PENDING → EXPIRED`, `TTL_EXPIRED`) em um único batch insert.
Depois, agrupar por `event_id`, **ordenar por `event_id`** (evita deadlock entre duas instâncias com lotes mistos) e aplicar um único `UPDATE events SET available = available + :soma WHERE id = :id` por evento. COMMIT.
Se o lote veio cheio, repetir até esvaziar (limite de iterações por execução). Métrica `reservations.expired`.

### 5.5 Cache de disponibilidade

- `GET /events/{id}` usa Caffeine (`booking.availability-cache.ttl: 1s`).
- Com round-robin, o mesmo cliente pode ver `48 → 50 → 48`. Esperado; a resposta para o code review está na seção 12.
- Perfil de teste: cache desabilitado (`booking.availability-cache.enabled=false`).

### 5.6 Tratamento de falhas de infraestrutura → 503 `DATABASE_BUSY` (+ `Retry-After: 1`)

- SQLState `55P03` (lock timeout), `57014` (statement timeout), `40P01` (deadlock)
- Pool esgotado (`SQLTransientConnectionException` / `CannotGetJdbcConnectionException`)

---

## 6. Configuração

```yaml
server:
  shutdown: graceful

spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      connection-timeout: 3000

booking:
  reservation:
    ttl: 10m
    max-quantity: 10
    expiration-job-enabled: true
    expiration-job-delay: 5s
    expiration-batch-size: 100
  availability-cache:
    enabled: true
    ttl: 1s

management:
  endpoints.web.exposure.include: health,info,metrics
```

Perfil `test`:
- `expiration-job-enabled: false` (os testes chamam o `ExpirationService` diretamente, de forma determinística);
- cache desabilitado;
- `lock_timeout` maior nos testes de carga funcional (5s), para não gerar 503 espúrios; um teste dedicado força o 503.

Cada instância: `INSTANCE_ID=api1|api2` (env) → header `X-Instance-Id`.

---

## 7. Docker

### Dockerfile (multi-stage)
`maven:3-eclipse-temurin-21` (build, `-DskipTests`) → `eclipse-temurin:21-jre` (runtime), usuário não root.

### docker-compose.yml

| Serviço | Detalhe |
|---|---|
| `postgres` | `postgres:16`, volume, `healthcheck` (`pg_isready`) |
| `api1`, `api2` | mesma imagem, `INSTANCE_ID` diferente, `depends_on: postgres: service_healthy`, healthcheck em `/actuator/health` |
| `nginx` | `8080:80`, upstream `api1:8080` + `api2:8080`, round-robin, repassa `X-Correlation-Id`, `proxy_next_upstream off` para POST |
| `k6` | `grafana/k6`, `profiles: ["load"]`, script montado de `./k6` |

Comandos:

```bash
docker compose up --build                      # sobe tudo em http://localhost:8080
docker compose --profile load run --rm k6 run /scripts/booking.js
./mvnw verify                                  # unitários + integração (Testcontainers)
```

Flyway: as duas APIs sobem juntas e o lock de migração do Flyway evita aplicação duplicada.

---

## 8. Testes

### 8.1 Unitários
Validações (quantity/capacity/name/key), montagem do hash canônico, mapeamento de exceções → códigos.

### 8.2 Integração (Testcontainers + Postgres real, `@SpringBootTest(RANDOM_PORT)`)

| # | Cenário | Esperado |
|---|---|---|
| I1 | Fluxo feliz dos 5 endpoints | status e corpos do contrato |
| I2 | Validações e catálogo de erros | códigos corretos, `correlationId` presente |
| I3 | **Concorrência:** capacity=50, 200 requisições simultâneas, `quantity=1`, chaves distintas | exatamente 50 × 201, 150 × 409, `available = 0` |
| I4 | **Idempotência concorrente:** 20 requisições, mesma chave | 1 reserva, 20 respostas equivalentes, estoque −1 uma vez, `Idempotent-Replayed` nas repetições |
| I5 | Mesma chave + outro payload / outro evento | 409 `IDEMPOTENCY_KEY_CONFLICT` |
| I6 | Erro de negócio não retém a chave | 409 → devolve estoque → retry com a mesma chave executa |
| I7 | **Expiração concorrente:** reservas vencidas (inseridas com `expires_at` no passado), dois `expireBatch()` em paralelo | cada reserva expira 1×, estoque devolvido 1× |
| I8 | Expiração com lote misto (2 eventos, 2 threads) | sem deadlock, estoque correto |
| I9 | **Cancelamento × expiração concorrentes** na mesma reserva | só um devolve estoque; o outro recebe 409/204 conforme a regra |
| I10 | `DELETE` repetido | 204 sem devolver estoque de novo |
| I11 | `DELETE` em reserva `PENDING` já vencida | 409 `INVALID_RESERVATION_STATE` |
| I12 | `GET` de reserva vencida antes do job | status `EXPIRED`, estoque ainda não devolvido |
| I13 | Evento inexistente em `POST reservations` | 404 `EVENT_NOT_FOUND` (via FK) |
| I14 | Lock/statement timeout forçado (tx segurando a linha) | 503 `DATABASE_BUSY` + `Retry-After` |
| I15 | Constraints e Flyway | `CHECK`s impedem estado inválido |
| I16 | **Histórico:** criar, cancelar, expirar; `DELETE` repetido; replay; 409 por falta de estoque | uma linha por transição efetiva (`CREATED`, `CANCELLED`, `EXPIRED`) com `correlation_id`; nada gravado em repetição ou rejeição |

**Invariante verificada ao fim de todo teste de escrita:**

```text
total_capacity = available + SUM(quantity WHERE status = 'PENDING')   e   available >= 0
```

### 8.3 k6 via nginx (concorrência real com as 2 instâncias)

`k6/booking.js`, cenários:
1. **200 VUs / 50 ingressos:** em caso de 503, refaz até 3× **com a mesma `Idempotency-Key`**. Esperado: exatamente 50 sucessos únicos, o restante 409, `available = 0`.
2. **20 requisições com a mesma chave:** 1 reserva; todas as respostas iguais.
3. **Distribuição:** contar `X-Instance-Id`; ambas as instâncias devem aparecer.

Thresholds: `http_req_failed` só conta 5xx inesperados (503 tratados por retry).

---

## 9. Observabilidade

- **Correlation ID:** `CorrelationIdFilter` (MDC) → todo log e todo erro carrega o id.
  Logs-chave: `reservation requested`, `capacity acquired`, `reservation created`, `commit completed`, `expired batch n=..`.
- **Actuator:** `/actuator/health` (usado pelos healthchecks), `/actuator/metrics`.
- **Métricas (Micrometer):** `reservations.created`, `reservations.cancelled`, `reservations.expired`, `reservations.rejected{reason=insufficient_capacity|idempotency_conflict|db_busy}`.
- **Swagger UI:** `/swagger-ui.html`, com exemplos de request/response e do `ProblemDetail`.
- **`requests.http`** (ou coleção Postman) com o roteiro de demonstração.

---

## 10. Fases de execução

| Fase | Entrega | Pronto quando |
|---|---|---|
| **0. Repo** | Git, `.gitignore`, `pom.xml`, `README` esqueleto | build compila |
| **1. Base** | Migrations V1–V4, `BookingProperties`, `GlobalExceptionHandler`, filtros (correlation/instance), `TxSupport` | app sobe e migra; erro padronizado funciona |
| **2. Eventos** | `POST /events`, `GET /events/{id}` (com cache) | I1 parcial, I2 (eventos) |
| **3. Reserva** | `POST reservations` + idempotência + histórico `CREATED` + `GET /reservations/{id}` (status efetivo) | I1, I4, I5, I6, I12, I13 |
| **4. Cancelar** | `DELETE` com as regras da seção 4 + histórico `CANCELLED` | I10, I11 |
| **5. Expiração** | Job + `ReservationExpirationService` (lote, `SKIP LOCKED`, agregado ordenado) + histórico `EXPIRED` | I7, I8, I9, I16 |
| **6. Resiliência** | Mapeamento 503, timeouts, pool | I14 |
| **7. Concorrência** | I3 + invariante em todos os testes | zero oversell provado |
| **8. Docker** | Dockerfile, Compose (2 APIs + nginx + healthchecks) | `docker compose up` sobe do zero |
| **9. k6** | Script e profile `load` | 3 cenários passam via nginx |
| **10. Docs** | Swagger, `requests.http`, métricas, README completo | checklist da seção 13 |
| **11. Ensaio** | Roteiro de apresentação | perguntas da seção 12 respondíveis sem consultar |

Commits pequenos, um por fase (ou por sub-entrega), mensagens no imperativo. O histórico também é avaliado.

---

## 11. README (estrutura)

1. Visão geral e diagrama (mermaid): arquitetura, fluxo de reserva, máquina de estados (`PENDING → CANCELLED | EXPIRED`).
2. Como rodar (`docker compose up --build`), como testar (`./mvnw verify`, k6), URLs (API, Swagger, health).
3. Contrato da API, erros e headers.
4. **Decisões arquiteturais** (tabela abaixo).
5. **Trade-offs** (hot row, cache local, chave não retida em erro, `available` inclui pendentes).
6. Premissas: sem confirmação/pagamento no PDF → reservas terminam `CANCELLED` ou `EXPIRED`.
7. Evoluções futuras (seção 14).

### Decisões e trade-offs

| Decisão | Por quê | Custo / trade-off |
|---|---|---|
| Estoque no Postgres com `UPDATE` atômico | garantia forte, simples | hot row em evento muito disputado |
| MyBatis com SQL explícito em XML, sem JPA | SQL crítico explícito (`resources/mapper`) e explicável | mais código manual de mapeamento |
| Idempotência com `INSERT ... ON CONFLICT` na mesma tx | sem estado `PROCESSING`, sem janela de race | erro de negócio não retém a chave |
| `UPDATE` de estoque por último na tx | menor tempo de lock | ordem menos “natural” de ler |
| Expiração com `SKIP LOCKED` + agregado por evento ordenado | N instâncias sem líder, sem deadlock | latência de até `job-delay` |
| Histórico append-only na mesma transação | auditoria e rastreio por `correlation_id`, sem inconsistência com o estado | um `INSERT` extra por transição (fora do `UPDATE` quente) |
| `GET` reserva com status efetivo | leitura sem efeito colateral | estoque só volta quando o job roda |
| Caffeine local | consistência eventual sem nova infra | valores diferentes entre instâncias por ~1s |
| Sem Kafka/Redis/Outbox | nenhum requisito exige | evolução futura descrita |
| 503 + `Retry-After` sob saturação | falha explícita em vez de fila infinita | cliente precisa reenviar (seguro com a chave) |

---

## 12. Roteiro de apresentação — perguntas prováveis

- **Como garante zero oversell?** `UPDATE ... WHERE available >= :q` atômico + `CHECK (available >= 0)`; provado por I3 e k6.
- **Por que não Redlock/Redis lock?** O Postgres já é a fonte da verdade e a operação é atômica.
- **Duas requisições com a mesma chave ao mesmo tempo?** `PRIMARY KEY` da chave; o segundo insert espera o commit do primeiro e devolve a resposta salva.
- **E se a transação falhar no meio?** Rollback desfaz reserva, chave e estoque juntos.
- **Como duas instâncias expiram sem conflito?** `FOR UPDATE SKIP LOCKED` + transição condicional; agregado ordenado evita deadlock.
- **Cancelar e expirar ao mesmo tempo?** O lock de linha da reserva serializa; só a transição `PENDING → X` devolve estoque.
- **Por que a disponibilidade pode oscilar (48 → 50 → 48)?** Cache local por instância + round-robin; consistência eventual só na leitura. A reserva nunca usa cache.
- **O que acontece sob saturação?** Timeouts de lock/pool viram 503 com `Retry-After`; o retry com a mesma chave é seguro.
- **E com 1 milhão de requisições no mesmo evento?** Hot row: fila de admissão/sala de espera, particionamento de estoque ou sharding.
- **Por que sem Kafka/pagamento?** Nenhum requisito do PDF justifica; consta como evolução.
- **Por que `I` nos services?** Contrato explícito e test doubles, assumindo a cerimônia com uma implementação.

---

## 13. Checklist de entrega (Definition of Done)

```text
[ ] docker compose up --build sobe tudo do zero (postgres healthy -> api1/api2 -> nginx)
[ ] 5 endpoints funcionando conforme o contrato (incl. Location e headers)
[ ] Migrations Flyway automáticas, sem conflito entre as 2 instâncias
[ ] Zero oversell provado (I3 e k6) + invariante de estoque em todos os testes
[ ] Idempotência concorrente provada (I4) e conflito coberto (I5, I6)
[ ] Expiração concorrente provada (I7, I8) e cancelamento x expiração (I9)
[ ] DELETE e GET consistentes para reserva vencida (I11, I12)
[ ] Histórico de reservas gravado na mesma transação de cada mudança de estado (I16)
[ ] 503 DATABASE_BUSY com Retry-After (I14)
[ ] k6 via nginx: 3 cenários passam, ambas as instâncias recebem tráfego
[ ] Erros padronizados com code + correlationId, sem stack trace
[ ] Swagger, /actuator/health, métricas e requests.http disponíveis
[ ] ./mvnw verify passa com um comando
[ ] README com decisões, trade-offs, premissas e evoluções
[ ] Histórico de commits limpo
[ ] Roteiro de apresentação ensaiado
```

---

## 14. Evoluções futuras (só no README)

Redis (cache compartilhado) · Kafka/SQS + Outbox · `CONFIRMED` + pagamento/estorno (saga) · clientes/locais/meia-entrada · fila de admissão/sala de espera · particionamento/sharding de estoque · rate limiting · OpenTelemetry · limpeza por TTL de `idempotency_keys` · invalidação de cache local em escrita.

Extras opcionais **se sobrar tempo** (não fazem parte do DoD): `POST /reservations/{id}/confirm` (adiciona `CONFIRMED`, que não expira), evict do cache local em escrita, endpoint de listagem de reservas por evento, `GET /reservations/{id}/history` para expor o histórico.

---

## 15. Ajustes aplicados sobre o v2

| Ponto | Ajuste |
|---|---|
| Testes de concorrência instáveis com 503 | I3 usa timeouts folgados no perfil de teste; k6 refaz 503 com a **mesma chave**; I14 cobre o 503 isolado |
| Pool esgotado não mapeado | `Hikari` timeout também vira 503 |
| `DELETE` × `GET` divergentes em reserva vencida | `AND expires_at > NOW()` no cancelamento; vencida → 409 |
| Deadlock na expiração com lote misto | agregado ordenado por `event_id` |
| FK antes do 404 | violação de FK (23503) vira `EVENT_NOT_FOUND`; o 0-linhas do estoque passa a significar só falta de estoque |
| Timestamps da resposta | vêm do banco (`RETURNING`), não do relógio da aplicação |
| `SET LOCAL` | aplicado no início de cada transação (`TxSupport`) |
| TTL em teste | expiração testada inserindo `expires_at` no passado e chamando o service; job desligado nos testes |
| 500 genérico e JSON malformado | `INTERNAL_ERROR` e `MALFORMED_REQUEST` no catálogo |
| Limite de quantidade | `max-quantity` (padrão 10) |
| Prova de balanceamento | header `X-Instance-Id` + contagem no k6 |
| k6 em “um comando” | serviço `k6` no Compose (profile `load`) |
| Coluna `key` | renomeada para `idempotency_key` |
| Invariante | `total_capacity = available + SUM(PENDING)` checada nos testes |
| Replay | header `Idempotent-Replayed: true` |
| Estrutura de packages | trocada de por domínio para **por camadas** (`controller`, `service`, `repository`, `model`) |
| Auditoria | tabela `reservation_history` (V4) gravada na mesma transação |
