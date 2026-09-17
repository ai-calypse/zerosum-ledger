-- decision: D05-7 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
-- Additive only (master §10.4): V2 created payout_runs, V4 is reconciliation's, and neither is edited.
--
-- The per-driver outcomes of a run, stored because the response has to be replayable. A replay must return what the
-- first call returned (D03-3), and the skipped drivers are exactly the part that cannot be recomputed: a driver
-- skipped as ineligible or already in flight leaves no attempt row behind, so re-deriving the answer from
-- payment_attempts would silently turn "skipped, and here is why" into "never mentioned".
--
-- One jsonb column rather than a payout_run_results table: these rows are written once with the run and only ever
-- read back whole, which is the same reasoning V4 applied to reconciliation_runs.report_lines.
ALTER TABLE payout_runs ADD COLUMN results jsonb NOT NULL DEFAULT '[]'::jsonb;

-- No grant needed: V2 already gives instruments_app table-level UPDATE on payout_runs, which covers columns added
-- later. The ALTER DEFAULT PRIVILEGES in V2 withholds UPDATE only from *new tables*, and this adds none.
