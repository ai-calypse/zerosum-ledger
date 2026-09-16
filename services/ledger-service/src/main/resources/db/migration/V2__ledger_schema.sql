-- decision: D02-1 — docs/step_02_ledger_core.md#decisions-and-outputs
-- Ledger schema (master §5.7 v1.2; §0.3 C8, C17, C18, C25). Applied by Flyway as ledger_owner (D00-4). Additive only.
-- Entity IDs, account codes and currencies use COLLATE "C": the database sort order then equals Java's
-- String.compareTo for the ASCII identifiers D01-5 allows, which the apply engine relies on for insert and lock order.

CREATE TABLE entities (
  entity_id  text COLLATE "C" PRIMARY KEY,
  kind       text NOT NULL CHECK (kind IN ('rider', 'driver', 'platform', 'provider')),
  last_seq   bigint NOT NULL DEFAULT 0 CHECK (last_seq >= 0),
  last_hash  bytea,                                          -- head of the entity's hash chain; null before its first row
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE accounts (
  entity_id     text COLLATE "C" NOT NULL REFERENCES entities (entity_id),
  account_code  text COLLATE "C" NOT NULL,
  currency      char(3) COLLATE "C" NOT NULL,
  normal_side   text NOT NULL CHECK (normal_side IN ('DEBIT', 'CREDIT')),
  balance_minor bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (entity_id, account_code, currency)
);

-- Append-only. One row per applied money order: the dedupe key, the M6 (a) link to the source idempotency key (C17)
-- and the optional source position, which the S02 test driver and SP1 runner leave null and S04 fills (D04-3).
CREATE TABLE applied_orders (
  order_id         uuid PRIMARY KEY,
  order_group_id   text NOT NULL,
  source_system    text NOT NULL,
  idempotency_key  text NOT NULL,
  kafka_topic      text,
  kafka_partition  int,
  kafka_offset     bigint,
  order_created_at timestamptz NOT NULL,
  applied_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT applied_orders_position_complete CHECK (
    (kafka_topic IS NULL) = (kafka_partition IS NULL) AND (kafka_partition IS NULL) = (kafka_offset IS NULL))
);

-- Append-only. One row per entry, per entity, gapless seq per entity; hash columns for the S1 chain (D02-6).
CREATE TABLE entity_changelog (
  entity_id           text COLLATE "C" NOT NULL,
  seq                 bigint NOT NULL CHECK (seq > 0),
  order_id            uuid NOT NULL,
  account_code        text COLLATE "C" NOT NULL,
  currency            char(3) COLLATE "C" NOT NULL,
  delta_minor         bigint NOT NULL CHECK (delta_minor <> 0),
  balance_after_minor bigint NOT NULL,
  hash_version        smallint NOT NULL,
  prev_hash           bytea,                                 -- null only for seq = 1
  row_hash            bytea NOT NULL,
  recorded_at         timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (entity_id, seq),
  CONSTRAINT entity_changelog_prev_hash CHECK ((seq = 1) = (prev_hash IS NULL))
);
CREATE INDEX entity_changelog_order ON entity_changelog (order_id);

-- decision: D02-9 — operational table, NOT append-only (§0.3 C18): resolved_at is updated when an operator resolves a row.
-- Surrogate key and nullable order_id so undecodable records fit (§0.3 C8). A Kafka position is unique, so a redelivered
-- record is quarantined once (§0.3 C25); null positions are distinct and never conflict.
CREATE TABLE quarantined_orders (
  quarantine_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  order_id        uuid,
  payload         bytea NOT NULL,
  error_code      text NOT NULL,
  error_detail    text,
  kafka_topic     text,
  kafka_partition int,
  kafka_offset    bigint,
  quarantined_at  timestamptz NOT NULL DEFAULT now(),
  resolved_at     timestamptz,
  CONSTRAINT quarantined_orders_position UNIQUE NULLS DISTINCT (kafka_topic, kafka_partition, kafka_offset)
);

-- ---------------------------------------------------------------------------------------------------------------------
-- decision: D02-2 — append-only enforcement pattern. Copy this block verbatim into another service database (S03, S05),
-- changing only the table names and the runtime role.
--   * row triggers reject UPDATE and DELETE, and a statement trigger rejects TRUNCATE, for every role including the owner;
--   * the runtime role additionally lacks those privileges, so it fails at the privilege check before any trigger;
--   * both paths raise SQLSTATE 42501 (insufficient_privilege), so callers see one error code.
--   * INSERT ... ON CONFLICT DO NOTHING performs no update and never fires the triggers.
-- Limitation: the table owner can still run DDL (drop or disable a trigger) and a superuser can bypass triggers.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE FUNCTION reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% is append-only (% rejected)', TG_TABLE_NAME, TG_OP USING ERRCODE = '42501';
END
$$;

CREATE TRIGGER applied_orders_no_update_delete BEFORE UPDATE OR DELETE ON applied_orders
  FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER applied_orders_no_truncate BEFORE TRUNCATE ON applied_orders
  FOR EACH STATEMENT EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER entity_changelog_no_update_delete BEFORE UPDATE OR DELETE ON entity_changelog
  FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER entity_changelog_no_truncate BEFORE TRUNCATE ON entity_changelog
  FOR EACH STATEMENT EXECUTE FUNCTION reject_mutation();

REVOKE UPDATE, DELETE, TRUNCATE ON applied_orders, entity_changelog FROM ledger_app;

-- ---------------------------------------------------------------------------------------------------------------------
-- decision: D02-1 — runtime and verifier grants (TB3, TB5).
-- S00 default privileges (D00-4) give ledger_app SELECT, INSERT, UPDATE and DELETE on every table ledger_owner creates,
-- and verifier SELECT. Narrow the runtime role: SELECT and INSERT everywhere, UPDATE only on mutable state, no DELETE.
-- ---------------------------------------------------------------------------------------------------------------------
REVOKE UPDATE, DELETE, TRUNCATE ON entities, accounts, quarantined_orders FROM ledger_app;
GRANT UPDATE ON entities, accounts TO ledger_app;
GRANT UPDATE (resolved_at) ON quarantined_orders TO ledger_app;

-- Tables added by later ledger migrations start without runtime UPDATE/DELETE; each migration grants what it needs.
-- (Narrows the D00-4 default privileges for the ledger database only; change request recorded in S02 I.2.)
ALTER DEFAULT PRIVILEGES FOR ROLE ledger_owner IN SCHEMA public REVOKE UPDATE, DELETE ON TABLES FROM ledger_app;
