-- decision: D03-6, D03-1 — docs/step_03_order_service_outbox.md#decisions-and-outputs
-- Order-service's own quarantine table for poison payment events (§0.3 C8, C25: every consuming service quarantines
-- in its own database). Shape reused from the ledger's quarantined_orders (D02-9), changing only the names.
CREATE TABLE quarantined_events (
  quarantine_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  -- Nullable: most poison never becomes an order, but a KeyReused breach names the order already holding the key.
  order_id        uuid,
  event_id        text,
  payload         bytea NOT NULL,
  error_code      text NOT NULL,
  error_detail    text,
  kafka_topic     text,
  kafka_partition int,
  kafka_offset    bigint,
  quarantined_at  timestamptz NOT NULL DEFAULT now(),
  resolved_at     timestamptz,
  -- A redelivered poison record must quarantine once, not once per delivery. NULLS DISTINCT so rows written outside a
  -- Kafka context (no coordinates) are never collapsed into one another.
  CONSTRAINT quarantined_events_position UNIQUE NULLS DISTINCT (kafka_topic, kafka_partition, kafka_offset)
);

-- decision: D03-6 — the quarantine table is deliberately NOT append-only: an operator resolves a row by setting
-- resolved_at. V3 revoked the default DML privileges for orders tables, so they are granted back here for this table
-- alone, and DELETE is still withheld — a quarantined event is evidence, and resolving it is not deleting it.
GRANT SELECT, INSERT, UPDATE ON quarantined_events TO orders_app;
GRANT USAGE, SELECT ON SEQUENCE quarantined_events_quarantine_id_seq TO orders_app;
