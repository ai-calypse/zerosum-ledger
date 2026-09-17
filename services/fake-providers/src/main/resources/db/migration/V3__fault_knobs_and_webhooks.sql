-- decision: D05-2, D05-3 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
-- Fault knobs, the injected-fault log (§0.3 E2) and the webhook delivery schedule (§0.3 E3). Additive only
-- (master §10.4): V2 is not edited, and the delivery state is added to provider_events as columns rather than
-- as a second table, because a delivery has exactly one event and a join would buy nothing.

-- ---------------------------------------------------------------------------------------------------------------------
-- The active fault profile per provider.
--
-- Persisted rather than held only in memory: a restart that silently dropped the profile would make a chaos run look
-- like it passed when the faults it was measuring had stopped being injected.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE fault_profiles (
  provider     text PRIMARY KEY,
  -- The whole knob set, as accepted. Stored whole so a profile written by a later version that added a knob
  -- (§0.3 C23 — S06-T01 adds the settlement-report discrepancy knobs) still round-trips through this table.
  knobs        jsonb  NOT NULL,
  -- Duplicated out of the JSON so a run can be identified by seed without parsing the profile (D01-10).
  seed         bigint NOT NULL,
  activated_at timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------------------------------------------------
-- decision: D05-2 (§0.3 E2) — every fault the simulator injects, with the seed that chose it.
--
-- Without this, a test that asserts "the system survived a fault" cannot show a fault was injected at all: a knob
-- that quietly stopped firing would turn every chaos test green.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE fault_log (
  fault_id    text PRIMARY KEY,
  provider    text   NOT NULL,
  fault_type  text   NOT NULL,
  -- What the fault hit: a request path, a payout id, an event id. Free text on purpose — the targets are of
  -- different kinds and a foreign key to any one of them would make the log refuse to record the others.
  target      text,
  seed        bigint NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX fault_log_occurred_at ON fault_log (occurred_at);

-- ---------------------------------------------------------------------------------------------------------------------
-- decision: D05-3 — webhook delivery state, on the event itself.
--
-- The redelivery schedule lives in the row, not in a timer: an event whose next attempt existed only in memory would
-- be stranded by a restart, and the receiver would never learn the outcome. delivered_at (V2) stays the single
-- statement that delivery is finished, and it is set only after a 2xx.
-- ---------------------------------------------------------------------------------------------------------------------
ALTER TABLE provider_events
  ADD COLUMN delivery_attempts   integer     NOT NULL DEFAULT 0,
  ADD COLUMN next_attempt_at     timestamptz NOT NULL DEFAULT now(),
  -- Set once when the reorder knob holds this event back, so an event can be held at most once and always makes
  -- progress. The minimum cut drops reorder simulation; this column is the only schema it costs.
  ADD COLUMN reorder_held        boolean     NOT NULL DEFAULT false,
  ADD COLUMN last_delivery_status smallint;

-- The sender only ever asks for events whose next attempt is due, so the index stays small.
CREATE INDEX provider_events_due ON provider_events (next_attempt_at) WHERE delivered_at IS NULL;

-- decision: D05-2 — runtime grants, matching V2: provider state changes, so UPDATE stays; DELETE does not, because
-- these rows are the evidence a fault was injected and a delivery was attempted.
REVOKE DELETE, TRUNCATE ON fault_profiles, fault_log FROM fakeproviders_app;
