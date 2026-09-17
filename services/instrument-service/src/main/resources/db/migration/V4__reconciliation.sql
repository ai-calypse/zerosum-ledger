-- decision: D06-2 — docs/step_06_reconciliation_verifier.md#decisions-and-outputs
-- Reconciliation runs and their typed breaks. Additive only (master §10.4): V2 is not edited.
--
-- V3 is deliberately left free for the webhook-receiver work landing in parallel; Flyway only requires versions to be
-- unique and ordered, so a gap costs nothing and a collision would cost a broken migration on both branches.

-- ---------------------------------------------------------------------------------------------------------------------
-- decision: D06-2 — one row per reconciliation of one provider's one closed day.
--
-- Two uniqueness rules, doing different jobs:
--   * idempotency_key  — the D03-3 rule, so a retried API call replays instead of reconciling twice;
--   * (provider, report_date) — the run IDENTITY, so two callers with DIFFERENT keys still produce one run for one
--     day. Without it, two schedulers or an operator racing the scheduler would each book the same settlement, and
--     the deterministic event id is the only thing that would stop the money being counted twice downstream.
--
-- The row is written once, complete, at the end of a run: the matcher is pure and runs before the transaction opens,
-- so there is nothing to update afterwards and the table needs no UPDATE grant at all.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE reconciliation_runs (
  run_id          uuid PRIMARY KEY DEFAULT uuidv7(),
  idempotency_key text NOT NULL CHECK (length(idempotency_key) <= 255),
  -- SHA-256 over the canonical request. A repeated key with a different body is a caller bug (D03-3), and answering
  -- it with the first run's result would hide that bug behind a success.
  request_hash    bytea NOT NULL,
  provider        text NOT NULL,
  report_date     date NOT NULL,
  report_id       text NOT NULL,
  -- The provider's content hash, so a report that changed between two runs is a typed break rather than a silently
  -- different set of numbers.
  content_hash    text NOT NULL,
  status          text NOT NULL CHECK (status IN ('COMPLETED','FAILED_VALIDATION')),
  -- The served report, stored whole. One column rather than a second table: these lines are written once with the
  -- run and only ever read back whole, so a table and its own append-only trigger would buy nothing.
  report_lines    jsonb NOT NULL,
  lines_matched   int NOT NULL DEFAULT 0,
  breaks_found    int NOT NULL DEFAULT 0,
  settled         boolean NOT NULL DEFAULT false,
  created_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT reconciliation_runs_idempotency UNIQUE (idempotency_key),
  CONSTRAINT reconciliation_runs_identity UNIQUE (provider, report_date)
);

-- ---------------------------------------------------------------------------------------------------------------------
-- decision: D06-2 — typed breaks, append-only (D02-2).
--
-- The TYPE is the product here, not the count. A matcher that flags every disagreement as "mismatch" satisfies a test
-- asserting "a break was raised" while telling an operator nothing about what to do, so the type is a checked column
-- and every discrepancy the simulator can inject maps to exactly one of these values (the D06-2 knob-to-break map,
-- which I12 reads):
--     report_missing_line   -> MISSING_IN_REPORT
--     report_off_by_one     -> AMOUNT_MISMATCH
--     report_duplicate_line -> DUPLICATE_LINE
--
-- A status change is a new row, never an update (D02-2), which is why the runtime role gets no UPDATE here.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE reconciliation_breaks (
  break_id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  run_id             uuid NOT NULL REFERENCES reconciliation_runs (run_id),
  break_type         text NOT NULL CHECK (break_type IN (
                       'MISSING_IN_LEDGER','MISSING_IN_REPORT','AMOUNT_MISMATCH','DUPLICATE_LINE',
                       'KIND_MISMATCH','CURRENCY_MISMATCH','TIMING','LINE_WITHOUT_SUCCESS','REPORT_ARITHMETIC')),
  -- OPEN is a break we cannot yet call wrong (the line may simply be in the next cycle); UNEXPLAINED is one we can.
  status             text NOT NULL CHECK (status IN ('OPEN','UNEXPLAINED')),
  provider_ref       text,
  client_reference   text,
  currency           char(3),
  -- What each side said, so an operator sees the disagreement itself rather than only its name. Null on a break that
  -- has no counterpart: a line missing from the report has no report amount, which is the point.
  report_gross_minor bigint,
  ledger_gross_minor bigint,
  attempt_id         uuid,
  detail             text,
  created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX reconciliation_breaks_run ON reconciliation_breaks (run_id, break_id);
CREATE INDEX reconciliation_breaks_open ON reconciliation_breaks (status) WHERE status = 'OPEN';

-- decision: D02-2 — the append-only pattern, reusing the reject_mutation() function V2 created. Both tables are
-- evidence: a run is the record that a day was reconciled, and a break is the record that money disagreed.
CREATE TRIGGER reconciliation_runs_no_update_delete BEFORE UPDATE OR DELETE ON reconciliation_runs
  FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER reconciliation_runs_no_truncate BEFORE TRUNCATE ON reconciliation_runs
  FOR EACH STATEMENT EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER reconciliation_breaks_no_update_delete BEFORE UPDATE OR DELETE ON reconciliation_breaks
  FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER reconciliation_breaks_no_truncate BEFORE TRUNCATE ON reconciliation_breaks
  FOR EACH STATEMENT EXECUTE FUNCTION reject_mutation();

-- decision: D05-4 — runtime grants. V2's ALTER DEFAULT PRIVILEGES already revokes UPDATE and DELETE on every table a
-- later migration creates, so instruments_app holds SELECT and INSERT here and nothing more, which is exactly what
-- append-only needs. The identity sequence is granted explicitly, as V2 does for quarantined_records.
GRANT USAGE, SELECT ON SEQUENCE reconciliation_breaks_break_id_seq TO instruments_app;
