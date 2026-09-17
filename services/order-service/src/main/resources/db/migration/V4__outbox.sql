-- decision: D03-5 — docs/step_03_order_service_outbox.md#decisions-and-outputs
-- Transactional outbox for the orders database. Additive only.
--
-- Migration ownership: CANDIDATE A. Each producing service keeps its own copy of this migration, and a libs/outbox
-- test fixture asserts the required columns and index. Candidate B (the library ships a migration location) would put
-- library-versioned migrations into every service's shared Flyway history table, where version numbers collide as soon
-- as two services adopt different library versions. A copied table definition is cheaper to keep honest than a shared
-- version space is to keep collision-free.
CREATE TABLE outbox (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  topic        text  NOT NULL,
  -- decision: ADR-0007 — the message key is the order group, so a group's records share a partition and stay ordered.
  message_key  text  NOT NULL,
  payload      jsonb NOT NULL,
  -- §0.3 C11 — W3C trace context captured at append time, so the relay can continue the trace from another thread.
  headers      jsonb NOT NULL DEFAULT '{}',
  created_at   timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);

-- The relay only ever scans unpublished rows, so the index stays small however large the table grows.
CREATE INDEX outbox_unpublished ON outbox (id) WHERE published_at IS NULL;

-- ---------------------------------------------------------------------------------------------------------------------
-- decision: D03-5 — the outbox is deliberately NOT append-only, unlike the money-order tables.
-- The relay marks rows published and the cleanup job deletes old ones, so the runtime role needs UPDATE and DELETE
-- here. V3 revoked them by default for later orders tables, so this migration grants them back for this table only.
-- ---------------------------------------------------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE, DELETE ON outbox TO orders_app;
GRANT USAGE, SELECT ON SEQUENCE outbox_id_seq TO orders_app;
