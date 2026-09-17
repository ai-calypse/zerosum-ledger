-- decision: D06-1 — docs/step_06_reconciliation_verifier.md#decisions-and-outputs
-- FakeCard settlement reports, one per closed simulated day. Additive only (master §10.4): V2 and V3 are not edited.
--
-- Both the clean report and the served report are stored. The difference between them IS the injected discrepancy,
-- so a test can show what the simulator corrupted without re-deriving it from the knobs — and a knob that silently
-- stopped firing cannot make a reconciliation suite green.
--
-- A report is generated once, on the first request for a closed day, and never regenerated. The primary key is what
-- makes that true under concurrency: two first requests race, one inserts, the other conflicts and reads the stored
-- row, so both callers see byte-identical content rather than two independently corrupted reports.
CREATE TABLE settlement_reports (
  provider     text NOT NULL,
  report_date  date NOT NULL,
  -- rpt_YYYY_MM_DD, as the golden O6 payload spells it (D01-9).
  report_id    text NOT NULL,
  -- The report as the provider's own records imply it, before any discrepancy knob.
  clean_lines  jsonb NOT NULL,
  -- What the endpoint actually serves. Equal to clean_lines when no knob fired.
  served_lines jsonb NOT NULL,
  -- SHA-256 over the served document. The reconciler stores it with its run, so a report that changed between two
  -- runs is a typed break rather than a silently different set of numbers.
  content_hash text NOT NULL,
  generated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (provider, report_date)
);

-- decision: D05-2 — runtime grants, matching V2 and V3. A persisted report is evidence: the simulator may write one,
-- never revise one. S00 default privileges (D00-4) grant UPDATE, so it is revoked explicitly rather than assumed;
-- V2's ALTER DEFAULT PRIVILEGES for this database revokes only DELETE.
REVOKE UPDATE, DELETE, TRUNCATE ON settlement_reports FROM fakeproviders_app;
