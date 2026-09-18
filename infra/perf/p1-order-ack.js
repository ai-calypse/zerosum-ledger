// P1: client-observed acknowledgement of POST /v1/money-orders (master §6.1 P1, §6.2 budget, perf matrix row "API ack").
// Open model: k6's constant-arrival-rate starts iterations on schedule whether or not earlier ones returned, so a slow
// server shows up as latency and dropped_iterations instead of silently lowering the offered load.
import http from "k6/http";
import exec from "k6/execution";
import { check } from "k6";

const RATE = Number(__ENV.RATE || 200);
const BASE = __ENV.BASE || "http://order-service:8081";
const RUN = __ENV.RUN_ID; // alphanumeric; makes every idempotency key and order group unique to this invocation
const RIDERS = 2000;
const DRIVERS = 500;
const FEE = 200;

export const options = {
  scenarios: {
    ack: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: __ENV.DURATION || "60s",
      preAllocatedVUs: Math.max(50, RATE / 5),
      maxVUs: Math.max(200, RATE),
    },
  },
  summaryTrendStats: ["min", "med", "avg", "p(90)", "p(95)", "p(99)", "max"],
  discardResponseBodies: true,
};

export default function () {
  const i = exec.scenario.iterationInTest;
  const fare = 1000 + (i % 50) * 10;
  const group = `p1_${RUN}_${i}`;
  const body = JSON.stringify({
    order_group_id: group,
    type: "COMMERCE",
    reason: "trip.completed",
    adjusts_order_id: null,
    entries: [
      { entity_id: `rider:p1${RUN}r${i % RIDERS}`, account: "receivable", currency: "USD", amount_minor: fare },
      { entity_id: `driver:p1${RUN}d${i % DRIVERS}`, account: "payable", currency: "USD", amount_minor: -(fare - FEE) },
      { entity_id: "platform:main", account: "revenue", currency: "USD", amount_minor: -FEE },
    ],
    metadata: { trip_id: group },
    effective_at: new Date().toISOString(),
  });
  const res = http.post(`${BASE}/v1/money-orders`, body, {
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${__ENV.WRITER_TOKEN}`,
      "Idempotency-Key": `p1-${RUN}-${i}`,
    },
  });
  check(res, { "201 Created": (r) => r.status === 201 });
}

export function handleSummary(data) {
  return { [`/results/${__ENV.LABEL}.json`]: JSON.stringify(data, null, 1) };
}
