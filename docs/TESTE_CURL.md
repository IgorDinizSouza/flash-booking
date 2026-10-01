# Roteiro de teste com curl (Case BackEnd - Flash Booking)

Todos os comandos abaixo foram executados contra a stack completa do Docker Compose (nginx + 2 APIs + PostgreSQL) e as respostas conferidas.
Use um terminal **bash** (Linux, macOS, WSL ou Git Bash no Windows) e a **mesma sessão** do início ao fim, porque os passos reaproveitam variáveis (`EID`, `RID`).

## 0. Subir o ambiente (tudo no Docker)

Pré-requisito: Docker com Docker Compose. Nada mais precisa estar instalado (Java e Maven rodam dentro do build da imagem).

O arquivo `credencias.env` **não está no repositório** (contém a senha do banco). Em uma máquina nova, crie-o a partir do modelo e, se quiser, troque a senha:

```bash
cp credencias.env.example credencias.env
```

Suba a stack (a primeira vez demora por causa do build):

```bash
docker compose up --build -d
```

Aguarde `api1`, `api2` e `postgres` ficarem `healthy` e confirme:

```bash
docker compose ps
```

```bash
curl -s http://localhost:8080/actuator/health
```

Resultado esperado: `{"status":"UP"}`. Variáveis usadas nos passos seguintes:

```bash
B=http://localhost:8080; H='Content-Type: application/json'
```

---

## 1. Requisitos funcionais (os 5 endpoints do PDF)

### 1.1 `POST /events` - criar evento

```bash
EID=$(curl -s -X POST $B/events -H "$H" -d '{"name":"Show Teste","capacity":5}' | sed -E 's/.*"id":"([^"]+)".*/\1/'); echo $EID
```

Esperado: 201 com `capacity` 5 e `available` 5. Para ver a resposta completa:

```bash
curl -s -i -X POST $B/events -H "$H" -d '{"name":"Show Teste","capacity":5}'
```

### 1.2 `GET /events/{id}` - consultar disponibilidade

```bash
curl -s $B/events/$EID
```

Esperado: 200, `"available":5`.

### 1.3 `POST /events/{id}/reservations` - reservar ingressos

```bash
RID=$(curl -s -X POST $B/events/$EID/reservations -H "$H" -H "Idempotency-Key: chave-001" -d '{"quantity":2}' | sed -E 's/.*"id":"([^"]+)".*/\1/'); echo $RID
```

Esperado: 201, `"status":"PENDING"`, com `expiresAt`. Resposta completa (headers `Location` e `X-Instance-Id`):

```bash
curl -s -i -X POST $B/events/$EID/reservations -H "$H" -H "Idempotency-Key: chave-002" -d '{"quantity":1}'
```

### 1.4 `GET /reservations/{id}` - consultar reserva

```bash
curl -s $B/reservations/$RID
```

Esperado: 200, `"status":"PENDING"`.

A disponibilidade do evento reflete a reserva (o `GET` do evento usa cache de ~1s, por isso a espera):

```bash
sleep 2; curl -s $B/events/$EID
```

Esperado: `available` reduzido (5 - 2 - 1 = 2).

### 1.5 `DELETE /reservations/{id}` - cancelar reserva

```bash
curl -s -o /dev/null -w "HTTP %{http_code}\n" -X DELETE $B/reservations/$RID
```

Esperado: `HTTP 204`. Cancelar de novo também retorna 204 e **não devolve estoque duas vezes**:

```bash
curl -s -o /dev/null -w "HTTP %{http_code}\n" -X DELETE $B/reservations/$RID
```

```bash
sleep 2; curl -s $B/reservations/$RID; echo; curl -s $B/events/$EID
```

Esperado: reserva `"status":"CANCELLED"` e `available` 4 (devolveu os 2 ingressos uma única vez).

---

## 2. Requisitos não funcionais

### 2.1 Idempotência

Mesma chave e mesmo corpo devolvem a **mesma reserva** (header `Idempotent-Replayed: true`):

```bash
curl -s -i -X POST $B/events/$EID/reservations -H "$H" -H "Idempotency-Key: chave-002" -d '{"quantity":1}' | grep -iE "^HTTP|^idempotent|^\{"
```

Mesma chave com corpo diferente: **409 `IDEMPOTENCY_KEY_CONFLICT`**:

```bash
curl -s -w "\nHTTP %{http_code}\n" -X POST $B/events/$EID/reservations -H "$H" -H "Idempotency-Key: chave-002" -d '{"quantity":3}'
```

Idempotência sob concorrência: 10 requisições simultâneas com a mesma chave devem gerar **uma única reserva** (uma linha só na saída) e descontar o estoque uma vez:

```bash
EID2=$(curl -s -X POST $B/events -H "$H" -d '{"name":"IdemParalela","capacity":10}' | sed -E 's/.*"id":"([^"]+)".*/\1/'); seq 1 10 | xargs -P 10 -I{} curl -s -X POST $B/events/$EID2/reservations -H "$H" -H "Idempotency-Key: mesma-chave-$EID2" -d '{"quantity":2}' | sed -E 's/.*"id":"([^"]+)".*/\1/' | sort | uniq -c
```

```bash
sleep 2; curl -s $B/events/$EID2
```

Esperado: `available` 8.

### 2.2 Nunca permitir oversell

Evento com 5 ingressos e 20 requisições **simultâneas** (chaves distintas, 1 ingresso cada). Esperado: exatamente 5 respostas `201` e 15 respostas `409`:

```bash
EID3=$(curl -s -X POST $B/events -H "$H" -d '{"name":"Oversell","capacity":5}' | sed -E 's/.*"id":"([^"]+)".*/\1/'); seq 1 20 | xargs -P 20 -I{} curl -s -o /dev/null -w "%{http_code}\n" -X POST $B/events/$EID3/reservations -H "$H" -H "Idempotency-Key: ov-$EID3-{}" -d '{"quantity":1}' | sort | uniq -c
```

```bash
sleep 2; curl -s $B/events/$EID3
```

Esperado: `"available":0` (nunca negativo). A resposta de um 409 por falta de estoque:

```bash
curl -s -w "\nHTTP %{http_code}\n" -X POST $B/events/$EID3/reservations -H "$H" -H "Idempotency-Key: sem-estoque" -d '{"quantity":1}'
```

Esperado: `INSUFFICIENT_CAPACITY`.

### 2.3 Múltiplas instâncias simultâneas da API

Oito requisições pelo nginx; o header `X-Instance-Id` deve mostrar `api1` e `api2` (metade cada):

```bash
for i in $(seq 1 8); do curl -s -D - -o /dev/null $B/events/$EID | grep -i "^x-instance-id"; done | sort | uniq -c
```

Prova de que a regra vale entre instâncias: o teste de oversell acima já passa pelas duas, e o k6 (seção 4) repete isso em carga.

### 2.4 Expiração automática de reservas pendentes

O prazo padrão é 10 minutos. Para ver a expiração em segundos, reduza o prazo no `credencias.env` e recrie as APIs:

```bash
sed -i -E 's/^BOOKING_RESERVATION_TTL=.*/BOOKING_RESERVATION_TTL=15s/' credencias.env && docker compose up -d --force-recreate api1 api2
```

Aguarde as APIs ficarem saudáveis (`docker compose ps`, ~20s). Crie uma reserva de 3 dos 4 ingressos:

```bash
EID4=$(curl -s -X POST $B/events -H "$H" -d '{"name":"Expira","capacity":4}' | sed -E 's/.*"id":"([^"]+)".*/\1/'); RID4=$(curl -s -X POST $B/events/$EID4/reservations -H "$H" -H "Idempotency-Key: exp-$EID4" -d '{"quantity":3}' | sed -E 's/.*"id":"([^"]+)".*/\1/'); sleep 2; curl -s $B/reservations/$RID4; echo; curl -s $B/events/$EID4
```

Esperado agora: `PENDING` e `available` 1. Aguarde cerca de 25 segundos (prazo de 15s + varredura do job a cada 5s) e consulte de novo:

```bash
sleep 25; curl -s $B/reservations/$RID4; echo; curl -s $B/events/$EID4
```

Esperado: `"status":"EXPIRED"` e `available` 4 (ingressos devolvidos). **Volte o prazo ao normal** ao terminar:

```bash
sed -i -E 's/^BOOKING_RESERVATION_TTL=.*/BOOKING_RESERVATION_TTL=10m/' credencias.env && docker compose up -d --force-recreate api1 api2
```

### 2.5 Consistência eventual na disponibilidade

`GET /events/{id}` usa cache local de 1s por instância. Logo após reservar, o valor pode estar defasado por até ~1s (e pode oscilar entre `api1` e `api2`). A **reserva** nunca usa cache: a decisão de estoque é sempre feita atomicamente no banco. Para observar:

```bash
curl -s $B/events/$EID; curl -s -X POST $B/events/$EID/reservations -H "$H" -H "Idempotency-Key: ec-$RANDOM" -d '{"quantity":1}' > /dev/null; curl -s $B/events/$EID; sleep 2; curl -s $B/events/$EID
```

### 2.6 Tratamento explícito de erros

Todos os erros seguem o formato `ProblemDetail` com `code` e `correlationId`:

| Situação | Comando | Esperado |
|---|---|---|
| Evento inexistente | `curl -s -w "\nHTTP %{http_code}\n" $B/events/00000000-0000-0000-0000-000000000000` | 404 `EVENT_NOT_FOUND` |
| Reserva inexistente | `curl -s -w "\nHTTP %{http_code}\n" -X DELETE $B/reservations/00000000-0000-0000-0000-000000000000` | 404 `RESERVATION_NOT_FOUND` |
| Falta `Idempotency-Key` | `curl -s -w "\nHTTP %{http_code}\n" -X POST $B/events/$EID/reservations -H "$H" -d '{"quantity":1}'` | 400 `MISSING_IDEMPOTENCY_KEY` |
| Capacidade inválida | `curl -s -w "\nHTTP %{http_code}\n" -X POST $B/events -H "$H" -d '{"name":"x","capacity":0}'` | 400 `INVALID_CAPACITY` |
| Quantidade inválida | `curl -s -w "\nHTTP %{http_code}\n" -X POST $B/events/$EID/reservations -H "$H" -H "Idempotency-Key: q0" -d '{"quantity":0}'` | 400 `INVALID_QUANTITY` |
| Quantidade decimal | `curl -s -w "\nHTTP %{http_code}\n" -X POST $B/events/$EID/reservations -H "$H" -H "Idempotency-Key: q25" -d '{"quantity":2.5}'` | 400 `MALFORMED_REQUEST` |
| UUID malformado | `curl -s -w "\nHTTP %{http_code}\n" $B/events/abc` | 400 `INVALID_ID_FORMAT` |
| Cancelar reserva expirada | (após 2.4) `curl -s -w "\nHTTP %{http_code}\n" -X DELETE $B/reservations/$RID4` | 409 `INVALID_RESERVATION_STATE` |
| Rota inexistente | `curl -s -w "\nHTTP %{http_code}\n" $B/nao-existe` | 404 `ROUTE_NOT_FOUND` |

---

## 3. Evidências no banco (opcional)

Histórico de cada mudança de estado das reservas (criada, cancelada, expirada), gravado na mesma transação:

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT action, previous_status, new_status, quantity, reason, instance_id FROM reservation_history ORDER BY id DESC LIMIT 10;"'
```

Invariante de estoque (`capacity = available + reservas PENDING`) para todos os eventos (esperado: nenhuma linha):

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT e.id FROM events e LEFT JOIN reservations r ON r.event_id=e.id AND r.status='"'"'PENDING'"'"' GROUP BY e.id HAVING e.total_capacity <> e.available + COALESCE(SUM(r.quantity),0);"'
```

## 4. Teste de carga e documentação interativa

Carga com k6 via nginx (200 requisições simultâneas disputando 50 ingressos; exige exatamente 50 sucessos e 150 conflitos, tráfego nas duas instâncias):

```bash
docker compose --profile load run --rm k6 run /scripts/booking.js
```

No Git Bash do Windows, prefixe com `MSYS_NO_PATHCONV=1`. Swagger UI: http://localhost:8080/swagger-ui.html

## 5. Encerrar

```bash
docker compose down
```

Use `docker compose down -v` apenas se quiser apagar também os dados do banco.

## 6. Postman

A coleção `docs/postman/FlashBooking.postman_collection.json` reproduz todo este roteiro, com as verificações (status, corpo e headers) já escritas em cada requisição e encadeamento automático de `eventId` e `reservationId`.

1. No Postman: **Import** e selecione o arquivo.
2. A variável `baseUrl` da coleção vale `http://localhost:8080` (Docker). Se for testar a aplicação rodando no IntelliJ, troque por `http://localhost:9090`.
3. Execute com **Run collection** (Collection Runner), em ordem. As pastas 1 a 5 devem passar inteiras (28 requisições, 75 verificações).
4. A pasta 4 (múltiplas instâncias) deve rodar com **8 iterações**; o Console do Postman mostra `api1` e `api2` alternando.
5. A pasta 6 (expiração) é **opcional** e só passa com `BOOKING_RESERVATION_TTL=15s` no `credencias.env` (veja a seção 2.4). Desmarque-a no Runner se estiver com o prazo padrão de 10 minutos.

Sem a interface, a mesma coleção roda por linha de comando (Docker):

```bash
docker run --rm -v "$PWD/docs/postman":/etc/newman postman/newman run FlashBooking.postman_collection.json --env-var "baseUrl=http://host.docker.internal:8080" --folder "1. Fluxo principal (os 5 endpoints do PDF)" --folder "2. Idempotencia" --folder "3. Sem oversell (sequencial)" --folder "4. Multiplas instancias" --folder "5. Erros explicitos"
```

O teste de oversell **concorrente** (20 requisições simultâneas) não é possível no Postman, porque o Runner é sequencial; use o `curl ... xargs -P` da seção 2.2 ou o k6.
