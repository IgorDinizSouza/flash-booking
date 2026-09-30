// Flash Booking - teste de carga/concorrencia via nginx (PLANO.md 8.3)
//
// Cenarios (executam em sequencia via startTime):
//   1. stampede     : 200 VUs simultaneos disputando 50 ingressos (chaves distintas)
//   2. same_key     : 20 requisicoes paralelas com a MESMA Idempotency-Key
//   3. distribution : contagem de X-Instance-Id (api1 e api2 devem aparecer)
//
// Uso: k6 run -e BASE_URL=http://localhost:8080 k6/booking.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const CAPACITY_STAMPEDE = 50;
const VUS_STAMPEDE = 200;
const CAPACITY_SAME_KEY = 10;
const QTY_SAME_KEY = 2;
const PARALLEL_SAME_KEY = 20;
const CACHE_TTL_WAIT_S = 1.5;
const MAX_RETRIES_503 = 3;

// 409 (capacidade esgotada / conflito de chave) e resposta esperada nos cenarios; 503 e 5xx nao.
http.setResponseCallback(http.expectedStatuses(200, 201, 204, 409));

const created201 = new Counter('reservations_created_201');
const insufficient409 = new Counter('reservations_insufficient_409');
const unexpected5xx = new Counter('unexpected_5xx');
const unexpectedOther = new Counter('unexpected_other');
const retries503 = new Counter('retries_503');
const instanceHits = new Counter('instance_hits');
const replayConsistent = new Rate('replay_consistent');
const finalAvailableOk = new Rate('final_available_ok');

export const options = {
  scenarios: {
    stampede: {
      executor: 'per-vu-iterations',
      vus: VUS_STAMPEDE,
      iterations: 1,
      maxDuration: '60s',
      exec: 'stampede',
      startTime: '0s',
    },
    same_key: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: '30s',
      exec: 'sameKey',
      startTime: '10s',
    },
    distribution: {
      executor: 'shared-iterations',
      vus: 4,
      iterations: 40,
      maxDuration: '30s',
      exec: 'distribution',
      startTime: '15s',
    },
  },
  thresholds: {
    http_req_failed: ['rate==0'],
    unexpected_5xx: ['count==0'],
    unexpected_other: ['count==0'],
    reservations_created_201: [`count==${CAPACITY_STAMPEDE}`],
    reservations_insufficient_409: [`count==${VUS_STAMPEDE - CAPACITY_STAMPEDE}`],
    replay_consistent: ['rate==1'],
    final_available_ok: ['rate==1'],
    'instance_hits{instance:api1}': ['count>0'],
    'instance_hits{instance:api2}': ['count>0'],
    checks: ['rate==1'],
  },
};

// ---------- helpers ----------
function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function track(res) {
  const inst = res.headers['X-Instance-Id'];
  if (inst) instanceHits.add(1, { instance: inst });
}

function reserveParams(key) {
  return {
    headers: Object.assign({ 'Idempotency-Key': key }, JSON_HEADERS),
    tags: { name: 'POST /events/{id}/reservations' },
  };
}

function retryAfterSeconds(res) {
  const v = parseInt(res.headers['Retry-After'], 10);
  return Number.isNaN(v) ? 1 : v;
}

function createEvent(name, capacity) {
  const res = http.post(BASE_URL + '/events', JSON.stringify({ name, capacity }), {
    headers: JSON_HEADERS,
    tags: { name: 'POST /events' },
  });
  if (res.status !== 201) {
    throw new Error(`setup: POST /events -> ${res.status} ${res.body}`);
  }
  return res.json();
}

function getEvent(id) {
  const res = http.get(`${BASE_URL}/events/${id}`, { tags: { name: 'GET /events/{id}' } });
  track(res);
  return res;
}

// ---------- setup / teardown ----------
export function setup() {
  const run = uuid().slice(0, 8);
  const stampede = createEvent(`k6 stampede ${run}`, CAPACITY_STAMPEDE);
  const sameKey = createEvent(`k6 same-key ${run}`, CAPACITY_SAME_KEY);
  const dist = createEvent(`k6 distribution ${run}`, 5);
  return { stampedeId: stampede.id, sameKeyId: sameKey.id, distId: dist.id };
}

export function teardown(data) {
  // Aguarda o TTL do cache de disponibilidade (1s) expirar em ambas as instancias
  sleep(CACHE_TTL_WAIT_S);
  // Varias leituras para que o round-robin passe pelas duas instancias
  for (let i = 0; i < 4; i++) {
    const s = getEvent(data.stampedeId);
    const okS = check(s, {
      'stampede: GET event 200': (r) => r.status === 200,
      'stampede: available == 0': (r) => r.status === 200 && r.json('available') === 0,
    });
    finalAvailableOk.add(okS);

    const k = getEvent(data.sameKeyId);
    const expected = CAPACITY_SAME_KEY - QTY_SAME_KEY;
    const okK = check(k, {
      'same_key: GET event 200': (r) => r.status === 200,
      'same_key: available == capacity - quantity': (r) =>
        r.status === 200 && r.json('available') === expected,
    });
    finalAvailableOk.add(okK);
  }
}

// ---------- cenario 1 ----------
export function stampede(data) {
  const url = `${BASE_URL}/events/${data.stampedeId}/reservations`;
  const body = JSON.stringify({ quantity: 1 });
  const key = uuid(); // a mesma chave e reutilizada nos retries de 503
  let res;
  for (let attempt = 0; attempt <= MAX_RETRIES_503; attempt++) {
    res = http.post(url, body, reserveParams(key));
    track(res);
    if (res.status !== 503) break;
    retries503.add(1);
    if (attempt < MAX_RETRIES_503) sleep(retryAfterSeconds(res));
  }

  if (res.status === 201) {
    created201.add(1);
    check(res, { '201 has reservation id': (r) => !!r.json('id') });
  } else if (res.status === 409 && res.json('code') === 'INSUFFICIENT_CAPACITY') {
    insufficient409.add(1);
  } else if (res.status >= 500) {
    unexpected5xx.add(1);
  } else {
    unexpectedOther.add(1);
    console.error(`stampede: unexpected ${res.status} ${res.body}`);
  }
}

// ---------- cenario 2 ----------
export function sameKey(data) {
  const url = `${BASE_URL}/events/${data.sameKeyId}/reservations`;
  const body = JSON.stringify({ quantity: QTY_SAME_KEY });
  const key = uuid();
  const params = reserveParams(key);

  let pending = Array.from({ length: PARALLEL_SAME_KEY }, (_, i) => i);
  const results = new Array(PARALLEL_SAME_KEY);

  for (let attempt = 0; attempt <= MAX_RETRIES_503 && pending.length > 0; attempt++) {
    const responses = http.batch(pending.map(() => ['POST', url, body, params]));
    const stillPending = [];
    responses.forEach((res, j) => {
      track(res);
      if (res.status === 503 && attempt < MAX_RETRIES_503) {
        retries503.add(1);
        stillPending.push(pending[j]);
      } else {
        results[pending[j]] = res;
      }
    });
    pending = stillPending;
    if (pending.length > 0) sleep(1);
  }

  const ids = new Set();
  let allOk = true;
  results.forEach((res) => {
    if (res.status === 201) {
      ids.add(res.json('id'));
    } else {
      allOk = false;
      if (res.status >= 500) unexpected5xx.add(1);
      else unexpectedOther.add(1);
      console.error(`same_key: unexpected ${res.status} ${res.body}`);
    }
  });
  const replays = results.filter((r) => r.headers['Idempotent-Replayed'] === 'true').length;

  replayConsistent.add(allOk && ids.size === 1);
  check(results, {
    'same_key: all responses 201': () => allOk,
    'same_key: single reservation id': () => ids.size === 1,
    'same_key: at most one non-replay': () => PARALLEL_SAME_KEY - replays <= 1,
  });
}

// ---------- cenario 3 ----------
export function distribution(data) {
  const res = getEvent(data.distId);
  if (res.status >= 500) unexpected5xx.add(1);
  check(res, {
    'distribution: 200': (r) => r.status === 200,
    'distribution: X-Instance-Id present': (r) => !!r.headers['X-Instance-Id'],
  });
}
