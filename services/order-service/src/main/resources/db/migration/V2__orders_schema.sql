-- decision: D03-1 — docs/step_03_order_service_outbox.md#decisions-and-outputs
-- Orders schema, after the D00-4 empty baseline. Additive only (master §10.4): later migrations add, never edit this.
--
-- The deferred zero-sum constraint triggers and the append-only triggers are deliberately NOT here: they belong to
-- S03-T02, which owns immutability enforcement and copies the D02-2 pattern. This migration creates the tables, the
-- idempotency uniqueness that makes replay a database property, and the runtime grants.

-- ---------------------------------------------------------------------------------------------------------------------
-- decision: D03-1 — order ids and timestamps are assigned by the DATABASE, not the application.
-- PostgreSQL 18 ships uuidv7() (master §4.1), so the default produces time-ordered ids with good index locality, and the
-- insert returns the stored id. That is what makes a replay return the ORIGINAL id and created_at rather than freshly
-- minted ones: the store never generates a candidate id it would have to discard when the insert conflicts.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE money_orders (
  order_id             uuid PRIMARY KEY DEFAULT uuidv7(),
  order_group_id       text NOT NULL,
  -- decision: D01-5 — rule 1 order types, re-checked in the database (†).
  type                 text NOT NULL CHECK (type IN ('COMMERCE','COLLECTION','REFUND','DISBURSEMENT','SETTLEMENT')),
  reason               text NOT NULL,
  -- decision: D01-5 — rule 6: existence is the foreign key's job; the same-group check runs in the application inside
  -- the insert transaction (§0.3 C2), because a foreign key cannot express it.
  adjusts_order_id     uuid REFERENCES money_orders (order_id),
  source_system        text NOT NULL,
  -- decision: D03-2 — 255 characters, following the Stripe convention recorded in master §2.5.
  idempotency_key      text NOT NULL CHECK (length(idempotency_key) <= 255),
  -- decision: D03-3 — SHA-256 over the canonical request form, with the form version stored beside it.
  -- The version is a COLUMN and not only a prefix inside the hashed bytes: a replay must be compared using the version
  -- the row was written under, and a tag that exists only inside the digest cannot be read back to choose the comparison.
  -- This is a deliberate addition to the master's proposed DDL, recorded in D03-1.
  request_hash         bytea NOT NULL,
  request_hash_version smallint NOT NULL,
  metadata             jsonb NOT NULL DEFAULT '{}',
  effective_at         timestamptz NOT NULL,
  created_at           timestamptz NOT NULL DEFAULT now(),
  -- decision: D01-5 — rule 8 (†). Idempotency is a database property; the store never checks then inserts.
  -- Scoped by source system, so the same key from two principals is two distinct orders (D03-3).
  CONSTRAINT money_orders_idempotency UNIQUE (source_system, idempotency_key)
);

-- Serves list-by-group ordered by creation time (S03-T03), which is a trip's full history.
CREATE INDEX money_orders_group ON money_orders (order_group_id, created_at);

CREATE TABLE money_order_entries (
  order_id      uuid     NOT NULL REFERENCES money_orders (order_id),
  -- Line numbers are part of the order: the same entity, account and currency may appear on several lines (§0.3 C6),
  -- and each line is applied separately by the ledger.
  line_no       smallint NOT NULL,
  entity_id     text     NOT NULL,
  account_code  text     NOT NULL,
  currency      char(3)  NOT NULL,
  -- decision: D01-5 — rule 2 (†): non-zero, magnitude at most 10^12 minor units.
  amount_minor  bigint   NOT NULL CHECK (amount_minor <> 0 AND abs(amount_minor) <= 1000000000000),
  PRIMARY KEY (order_id, line_no)
);

-- ---------------------------------------------------------------------------------------------------------------------
-- decision: D03-1 — runtime grants (TB1, TB3).
-- S00 default privileges (D00-4) give orders_app SELECT, INSERT, UPDATE and DELETE on every table orders_owner creates.
-- Money orders are append-only, so the runtime role keeps only SELECT and INSERT. The triggers that also stop the OWNER
-- are S03-T02's; privileges alone would leave the owner able to mutate (master §3.1 M2 (a)).
-- ---------------------------------------------------------------------------------------------------------------------
REVOKE UPDATE, DELETE, TRUNCATE ON money_orders, money_order_entries FROM orders_app;

-- Tables added by later orders migrations start without runtime UPDATE/DELETE; each migration grants what it needs.
-- Follows the CR-S02-01 precedent accepted for the ledger database, applied here to the orders database (D03-1).
ALTER DEFAULT PRIVILEGES FOR ROLE orders_owner IN SCHEMA public REVOKE UPDATE, DELETE ON TABLES FROM orders_app;
