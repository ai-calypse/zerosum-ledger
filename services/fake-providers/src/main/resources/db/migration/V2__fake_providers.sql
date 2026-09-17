-- decision: D05-2 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
-- Ground truth for the two simulated providers, after the D00-4 empty baseline. Additive only (master §10.4).
--
-- These tables are NOT append-only, unlike the money-order and ledger tables. A payout genuinely changes state as the
-- simulated bank processes it, and a charge records refunds against itself. The immutability guarantees in this system
-- are ours; an external provider's own database is not bound by them, and pretending otherwise would make the
-- simulation less faithful rather than safer.
--
-- What is protected instead: nothing may be DELETED. These rows are the ground truth invariant I7 is checked against,
-- so losing one would destroy the evidence that the ledger agrees with the provider.

-- ---------------------------------------------------------------------------------------------------------------------
-- FakeCard — synchronous, idempotency keys, charges and refunds (master §5.9)
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE card_charges (
  charge_id        text PRIMARY KEY,
  -- The attempt id, as the caller sent it. Not unique: a caller without a stored idempotency record may legitimately
  -- submit twice, and the point of this simulator is that such a duplicate is VISIBLE rather than prevented.
  client_reference text   NOT NULL,
  instrument_token text   NOT NULL,
  amount_minor     bigint NOT NULL CHECK (amount_minor > 0),
  currency         char(3) NOT NULL,
  status           text   NOT NULL CHECK (status IN ('SUCCEEDED','DECLINED')),
  decline_code     text,
  -- decision: D01-3 — 290 bps + 30 minor units per capture, HALF_EVEN. Charged on capture, never returned on refund.
  fee_minor        bigint NOT NULL DEFAULT 0 CHECK (fee_minor >= 0),
  refunded_minor   bigint NOT NULL DEFAULT 0 CHECK (refunded_minor >= 0),
  created_at       timestamptz NOT NULL DEFAULT now(),
  -- A refund may never exceed what was captured. Enforced here as well as in the application: this is the rule that
  -- would let a bug hand back money that was never collected.
  CONSTRAINT card_charges_refund_within_capture CHECK (refunded_minor <= amount_minor)
);

CREATE INDEX card_charges_client_reference ON card_charges (client_reference);

CREATE TABLE card_refunds (
  refund_id        text PRIMARY KEY,
  charge_id        text   NOT NULL REFERENCES card_charges (charge_id),
  client_reference text   NOT NULL,
  amount_minor     bigint NOT NULL CHECK (amount_minor > 0),
  status           text   NOT NULL CHECK (status IN ('SUCCEEDED','FAILED')),
  failure_code     text,
  created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX card_refunds_client_reference ON card_refunds (client_reference);
CREATE INDEX card_refunds_charge ON card_refunds (charge_id);

-- ---------------------------------------------------------------------------------------------------------------------
-- decision: D05-2 — idempotency is a DATABASE property here, exactly as it is in order-service (D03-3).
--
-- The primary key is what makes a concurrent burst with one key produce one charge: the second inserter conflicts
-- rather than reading a not-yet-committed row and deciding it may proceed. A check-then-insert in the application
-- would pass its own tests and duplicate under load.
--
-- The stored response is kept whole, so a replay returns bytes identical to the original rather than a freshly
-- rendered response that might differ after a code change.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE idempotency_records (
  provider            text     NOT NULL,
  idempotency_key     text     NOT NULL CHECK (length(idempotency_key) <= 255),
  -- SHA-256 over the canonical request. A repeated key with a different body is a caller bug, and answering it with
  -- the first response would hide that bug behind a success.
  request_fingerprint bytea    NOT NULL,
  response_status     smallint NOT NULL,
  response_body       jsonb    NOT NULL,
  created_at          timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (provider, idempotency_key)
);

-- ---------------------------------------------------------------------------------------------------------------------
-- FakeBank — asynchronous, NO idempotency, duplicates deliberately accepted (master §5.9, M7, W4)
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE bank_payouts (
  payout_id         text PRIMARY KEY,
  -- decision: D05-2 — deliberately NOT unique. FakeBank accepts a second submission of the same client reference and
  -- creates a second payout. This asymmetry with FakeCard is the entire reason FakeBank exists: it is what forces the
  -- resolver to look up before resubmitting instead of retrying blindly.
  client_reference  text   NOT NULL,
  destination_token text   NOT NULL,
  amount_minor      bigint NOT NULL CHECK (amount_minor > 0),
  currency          char(3) NOT NULL,
  status            text   NOT NULL CHECK (status IN ('PENDING','SETTLED','FAILED','RETURNED')),
  return_code       text,
  accepted_at       timestamptz NOT NULL DEFAULT now(),
  -- When the simulated banking day next turns for this payout. Persisted rather than held in memory so the lifecycle
  -- survives a restart: a payout that vanished from the schedule would sit PENDING forever and look like a bug in the
  -- resolver rather than in the simulator.
  process_at        timestamptz NOT NULL,
  settled_at        timestamptz,
  returned_at       timestamptz
);

CREATE INDEX bank_payouts_client_reference ON bank_payouts (client_reference);
-- The scheduler only ever asks for payouts whose next transition is due, so the index stays small.
CREATE INDEX bank_payouts_due ON bank_payouts (process_at) WHERE status IN ('PENDING','SETTLED');

-- ---------------------------------------------------------------------------------------------------------------------
-- decision: D05-2 — the outgoing event log, written by both providers.
--
-- Every terminal outcome is recorded here in the same transaction that changes the payout or charge. The webhook
-- sender (S05-T03) drains it. That task is deferred, so nothing delivers these today and the instrument adapters
-- resolve outcomes by lookup instead — the log is still written, so enabling delivery later needs no schema change.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE provider_events (
  event_id         text PRIMARY KEY,
  provider         text   NOT NULL,
  event_type       text   NOT NULL,
  provider_ref     text   NOT NULL,
  client_reference text   NOT NULL,
  amount_minor     bigint NOT NULL,
  currency         char(3) NOT NULL,
  failure_code     text,
  occurred_at      timestamptz NOT NULL DEFAULT now(),
  -- Set by the webhook sender once delivery is acknowledged; NULL means undelivered, not undeliverable.
  delivered_at     timestamptz
);

CREATE INDEX provider_events_undelivered ON provider_events (occurred_at) WHERE delivered_at IS NULL;
CREATE INDEX provider_events_client_reference ON provider_events (client_reference);

-- ---------------------------------------------------------------------------------------------------------------------
-- decision: D05-2 — runtime grants.
-- S00 default privileges (D00-4) give fakeproviders_app SELECT, INSERT, UPDATE and DELETE on every table the owner
-- creates. Provider state legitimately changes, so UPDATE stays. DELETE does not: these rows are the ground truth that
-- invariant I7 compares the ledger against, and a deleted row would remove the evidence rather than the discrepancy.
-- ---------------------------------------------------------------------------------------------------------------------
REVOKE DELETE, TRUNCATE ON card_charges, card_refunds, idempotency_records, bank_payouts, provider_events
  FROM fakeproviders_app;

ALTER DEFAULT PRIVILEGES FOR ROLE fakeproviders_owner IN SCHEMA public REVOKE DELETE ON TABLES FROM fakeproviders_app;
