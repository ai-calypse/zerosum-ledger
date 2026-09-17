-- decision: D03-1 — docs/step_03_order_service_outbox.md#decisions-and-outputs
-- Immutability and the commit-time zero-sum check. Additive only.

-- ---------------------------------------------------------------------------------------------------------------------
-- decision: D02-2 — append-only enforcement pattern, copied from the ledger migration with the table names and the
-- runtime role changed. Row triggers reject UPDATE and DELETE and a statement trigger rejects TRUNCATE for every role
-- INCLUDING the owner; the runtime role additionally lacks the privileges, so it fails at the privilege check first.
-- Both paths raise SQLSTATE 42501, so callers see one code. INSERT ... ON CONFLICT DO NOTHING never fires them.
-- Limitation: the owner can still run DDL (drop or disable a trigger) and a superuser bypasses triggers (H.5).
-- The outbox table is deliberately NOT covered: S03-T05 needs UPDATE and DELETE on it.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE FUNCTION reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% is append-only (% rejected)', TG_TABLE_NAME, TG_OP USING ERRCODE = '42501';
END
$$;

CREATE TRIGGER money_orders_no_update_delete BEFORE UPDATE OR DELETE ON money_orders
  FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER money_orders_no_truncate BEFORE TRUNCATE ON money_orders
  FOR EACH STATEMENT EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER money_order_entries_no_update_delete BEFORE UPDATE OR DELETE ON money_order_entries
  FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER money_order_entries_no_truncate BEFORE TRUNCATE ON money_order_entries
  FOR EACH STATEMENT EXECUTE FUNCTION reject_mutation();

-- ---------------------------------------------------------------------------------------------------------------------
-- decision: D03-1 — commit-time zero-sum check (M2 (b)).
-- A CONSTRAINT TRIGGER must be AFTER ROW; DEFERRABLE INITIALLY DEFERRED makes it run at COMMIT, so a whole order is
-- visible by then even though its rows arrive one statement at a time.
-- Attached to BOTH tables (§0.3 C3): on entries so an unbalanced set fails, and on the header so an order committed
-- with NO entries fails too — the entries trigger alone would never fire for a header with nothing attached.
-- ponytail: runs once per entry row (at most 50 per order). S07 measures P1; optimise only on that evidence.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE FUNCTION assert_order_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  target uuid := NEW.order_id;
BEGIN
  IF (SELECT count(*) FROM money_order_entries WHERE order_id = target) < 2
     OR EXISTS (SELECT 1 FROM money_order_entries WHERE order_id = target
                GROUP BY currency HAVING sum(amount_minor) <> 0) THEN
    RAISE EXCEPTION 'money order % is not zero-sum', target USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER money_order_entries_zero_sum AFTER INSERT ON money_order_entries
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_order_balanced();
CREATE CONSTRAINT TRIGGER money_orders_have_entries AFTER INSERT ON money_orders
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_order_balanced();
