-- decision: D05-4 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
-- The instruments schema, after the D00-4 empty baseline. Additive only (master §10.4).
--
-- Reconciliation runs and breaks are deliberately NOT created here; they belong to S06.

-- ---------------------------------------------------------------------------------------------------------------------
-- decision: D05-4 — one row per attempt to move money through a provider.
--
-- `version` is the optimistic guard. Every status change runs
--     UPDATE payment_attempts SET status=:to, version=version+1 WHERE attempt_id=:id AND version=:v AND status=:from
-- so two actors racing the same attempt cannot both win: the loser updates zero rows and re-reads. Without it, a
-- webhook and a sweeper arriving together could both believe they moved the attempt and both emit an event.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE payment_attempts (
  attempt_id         uuid PRIMARY KEY DEFAULT uuidv7(),
  kind               text NOT NULL CHECK (kind IN ('CHARGE','REFUND','PAYOUT')),
  order_group_id     text NOT NULL,
  -- The COMMERCE order that triggered a charge or refund. Null for payouts, which are driven by a run.
  source_order_id    uuid,
  payout_run_id      uuid,
  entity_id          text NOT NULL,
  provider           text NOT NULL,
  instrument_token   text NOT NULL,
  currency           char(3) NOT NULL,
  -- Magnitude only; direction is carried by `kind`, matching the instrument layer (ADR-0003, ADR-0010).
  amount_minor       bigint NOT NULL CHECK (amount_minor > 0),
  status             text NOT NULL CHECK (status IN (
                       'CREATED','SUBMITTING','PENDING','SUCCEEDED','DECLINED','FAILED',
                       'SETTLED','RETURNED','CANCELLED','UNKNOWN','NEEDS_REVIEW')),
  provider_ref       text,
  failure_code       text,
  -- A refund whose original charge has not resolved yet cannot be submitted; it waits rather than being lost.
  blocked_on_capture boolean NOT NULL DEFAULT false,
  version            bigint NOT NULL DEFAULT 0,
  -- When the sweeper should next look at this attempt. Null once the attempt is terminal.
  next_check_at      timestamptz,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  -- §0.3 C22 — currency is part of the key: one order may legitimately charge a rider in two currencies, and without
  -- it the second charge would collide with the first and silently never happen.
  CONSTRAINT payment_attempts_one_per_order UNIQUE (kind, source_order_id, entity_id, currency)
);

-- ---------------------------------------------------------------------------------------------------------------------
-- decision: D05-4, M10(a) — at most one payout in flight per (entity, currency), enforced by the database.
--
-- A partial unique index rather than application logic: the check and the insert would otherwise be two statements,
-- and two concurrent payout runs would both pass the check and both pay the driver.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE UNIQUE INDEX one_inflight_payout ON payment_attempts (entity_id, currency)
  WHERE kind = 'PAYOUT' AND status IN ('CREATED','SUBMITTING','PENDING','UNKNOWN');

-- The sweeper asks only for attempts whose next check is due, so the index stays small however many attempts exist.
CREATE INDEX attempts_due ON payment_attempts (next_check_at)
  WHERE status IN ('CREATED','SUBMITTING','UNKNOWN');

CREATE INDEX attempts_by_group ON payment_attempts (order_group_id, created_at);
CREATE INDEX attempts_by_run ON payment_attempts (payout_run_id) WHERE payout_run_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------------------------------
-- decision: D05-4 — append-only transition history. Every status change writes one row, with the cause that produced
-- it, so "why is this attempt in NEEDS_REVIEW" is answered by reading rows rather than by correlating logs.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE attempt_transitions (
  attempt_id  uuid NOT NULL REFERENCES payment_attempts (attempt_id),
  seq         int  NOT NULL,
  from_status text,
  to_status   text NOT NULL,
  cause       text NOT NULL,
  at          timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (attempt_id, seq)
);

-- ---------------------------------------------------------------------------------------------------------------------
-- decision: D05-4 — provider events, recorded before they are applied (master §5.10).
--
-- The primary key is the provider's own event id, so a webhook redelivered five times is recorded once. Dedupe is a
-- database property here for the same reason it is for money orders: a check-then-insert duplicates under load.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE provider_events (
  provider          text  NOT NULL,
  provider_event_id text  NOT NULL,
  attempt_id        uuid  REFERENCES payment_attempts (attempt_id),
  payload           jsonb NOT NULL,
  received_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (provider, provider_event_id)
);

CREATE INDEX provider_events_attempt ON provider_events (attempt_id);

-- Which instrument token to use for an entity at a provider. One token per (entity, provider).
CREATE TABLE instrument_tokens (
  entity_id     text NOT NULL,
  provider      text NOT NULL,
  token         text NOT NULL,
  registered_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (entity_id, provider)
);

-- ---------------------------------------------------------------------------------------------------------------------
-- decision: D05-4 — payout runs. Idempotent by key, like money orders (D03-3): the row itself is the idempotency
-- record, so a retried run returns the original rather than paying everyone twice.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE payout_runs (
  run_id               uuid PRIMARY KEY DEFAULT uuidv7(),
  idempotency_key      text NOT NULL CHECK (length(idempotency_key) <= 255),
  request_hash         bytea NOT NULL,
  request_hash_version smallint NOT NULL,
  currency             char(3) NOT NULL,
  status               text NOT NULL CHECK (status IN ('RUNNING','COMPLETED','REFUSED')),
  -- Set when the run is refused, e.g. ledger_stale or payouts disabled (M10(c)).
  refusal_code         text,
  attempts_created     int  NOT NULL DEFAULT 0,
  created_at           timestamptz NOT NULL DEFAULT now(),
  completed_at         timestamptz,
  CONSTRAINT payout_runs_idempotency UNIQUE (idempotency_key)
);

ALTER TABLE payment_attempts
  ADD CONSTRAINT payment_attempts_run_fk FOREIGN KEY (payout_run_id) REFERENCES payout_runs (run_id);

-- ---------------------------------------------------------------------------------------------------------------------
-- decision: D05-4 — this service's own quarantine table (§0.3 C8: every consuming service quarantines in its own
-- database). Shape reused from order-service's quarantined_events (D03-6), changing only the names.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE quarantined_records (
  quarantine_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  attempt_id      uuid,
  event_id        text,
  payload         bytea NOT NULL,
  error_code      text NOT NULL,
  error_detail    text,
  kafka_topic     text,
  kafka_partition int,
  kafka_offset    bigint,
  quarantined_at  timestamptz NOT NULL DEFAULT now(),
  resolved_at     timestamptz,
  -- NULLS DISTINCT so rows written outside a Kafka context are never collapsed into one another.
  CONSTRAINT quarantined_records_position UNIQUE NULLS DISTINCT (kafka_topic, kafka_partition, kafka_offset)
);

-- ---------------------------------------------------------------------------------------------------------------------
-- decision: D03-5 — the libs/outbox table, copied rather than shared. The library owns the code; each service owns
-- its table, so two services adopting different library versions cannot collide in one Flyway version space.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE outbox (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  topic        text  NOT NULL,
  -- decision: ADR-0007 — keyed by the order group, so a group's records share a partition and stay ordered.
  message_key  text  NOT NULL,
  payload      jsonb NOT NULL,
  headers      jsonb NOT NULL DEFAULT '{}',
  created_at   timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);

CREATE INDEX outbox_unpublished ON outbox (id) WHERE published_at IS NULL;

-- ---------------------------------------------------------------------------------------------------------------------
-- decision: D02-2 — append-only enforcement pattern, copied verbatim from the ledger database as that block directs,
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

CREATE TRIGGER attempt_transitions_no_update_delete BEFORE UPDATE OR DELETE ON attempt_transitions
  FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER attempt_transitions_no_truncate BEFORE TRUNCATE ON attempt_transitions
  FOR EACH STATEMENT EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER provider_events_no_update_delete BEFORE UPDATE OR DELETE ON provider_events
  FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER provider_events_no_truncate BEFORE TRUNCATE ON provider_events
  FOR EACH STATEMENT EXECUTE FUNCTION reject_mutation();

-- ---------------------------------------------------------------------------------------------------------------------
-- decision: D05-4 — runtime grants (TB2, TB3).
-- S00 default privileges (D00-4) give instruments_app SELECT, INSERT, UPDATE and DELETE on every table the owner
-- creates. Narrow that: history and provider events are append-only, and nothing may be deleted anywhere except the
-- outbox, whose rows are transport rather than evidence.
-- ---------------------------------------------------------------------------------------------------------------------
REVOKE UPDATE, DELETE, TRUNCATE ON attempt_transitions, provider_events FROM instruments_app;
REVOKE DELETE, TRUNCATE ON payment_attempts, payout_runs, instrument_tokens, quarantined_records FROM instruments_app;

-- Attempts and runs legitimately change state; that is what the optimistic guard protects.
GRANT UPDATE ON payment_attempts, payout_runs TO instruments_app;
GRANT UPDATE ON instrument_tokens TO instruments_app;
-- An operator resolves a quarantined record by setting resolved_at. Resolving is not deleting.
GRANT UPDATE (resolved_at, attempt_id) ON quarantined_records TO instruments_app;
-- The relay marks rows published and the cleanup job deletes old ones (D03-5).
GRANT SELECT, INSERT, UPDATE, DELETE ON outbox TO instruments_app;
GRANT USAGE, SELECT ON SEQUENCE outbox_id_seq TO instruments_app;
GRANT USAGE, SELECT ON SEQUENCE quarantined_records_quarantine_id_seq TO instruments_app;

-- Tables added by later instruments migrations start without runtime UPDATE/DELETE; each migration grants what it needs.
ALTER DEFAULT PRIVILEGES FOR ROLE instruments_owner IN SCHEMA public
  REVOKE UPDATE, DELETE ON TABLES FROM instruments_app;
