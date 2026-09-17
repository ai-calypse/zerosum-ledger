# Operational runbook

Procedures an operator follows when something needs attention. Each one states what it fixes, what it does **not**
fix, and how to tell it worked.

> Created by S04-T03. The step assumed this file already existed; it did not, so it starts here with the quarantine
> procedure and grows as later steps add their own.

<a id="quarantine-republish"></a>
## Re-publishing a quarantined record (D04-4)

**When:** a record is in `quarantined_orders` (ledger-service) or `quarantined_events` (order-service) with
`resolved_at IS NULL`, and the cause has been fixed.

**Why it is manual:** the admin retry endpoint is deferred (master §0.3 C14). Re-publishing money is rare and
consequential, so it uses the stock Kafka CLI rather than a tool written for it — a tool would need its own tests,
its own auth story, and would be used perhaps twice a year.

**What makes this safe:** applying an order twice is a no-op. The ledger's `applied_orders` table keys on the order
id, so a re-published record that was in fact already applied is skipped rather than double-counted. That is why the
procedure below does not require you to prove the order was never applied.

### Steps

1. **Find the row.**

   ```sql
   SELECT quarantine_id, order_id, error_code, error_detail, kafka_topic, kafka_partition, kafka_offset, quarantined_at
   FROM quarantined_orders
   WHERE resolved_at IS NULL
   ORDER BY quarantined_at;
   ```

   `error_code` tells you what to fix:

   | Code | Meaning | Typical fix |
   |---|---|---|
   | `UNDECODABLE_PAYLOAD` | Not JSON, or no readable order id | Fix the producer; the original bytes are in `payload` and on the DLQ |
   | `SCHEMA_INVALID` | Fails the D01-8 money-order schema, or an unknown schema version | Usually a producer shipped a change before consumers were ready |
   | `STRUCTURALLY_INVALID` | Entries do not sum to zero per currency, or an account is not allowed for that entity kind | A real defect in whatever produced the order. Do **not** hand-edit the payload to make it balance |
   | `ARITHMETIC_OVERFLOW` | A balance or sequence number would overflow | Escalate; this is not a re-publish case |
   | `UNEXPECTED_DATABASE_ERROR` | A non-transient database error for this record alone | Investigate the database first |

2. **Fix the cause.** Re-publishing before the cause is fixed simply re-quarantines the record, at a new offset, with
   a second quarantine row.

3. **Recover the original bytes.** Either from the quarantine row:

   ```sql
   SELECT convert_from(payload, 'UTF8') FROM quarantined_orders WHERE quarantine_id = <id>;
   ```

   or from the dead-letter topic, where the record kept its original key, value and headers:

   ```bash
   docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
     --bootstrap-server kafka:29092 \
     --topic payments.money-orders.v1.dlq \
     --from-beginning --property print.key=true --property print.headers=true
   ```

   The `zs-dlq-original-topic`, `zs-dlq-original-partition` and `zs-dlq-original-offset` headers identify the record;
   `zs-dlq-error-code` matches the quarantine row.

4. **Re-publish to the source topic with the original key.** The key must be the order group — publishing under a
   different key puts the record on another partition and breaks its ordering against the rest of its group
   (ADR-0007).

   ```bash
   docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
     --bootstrap-server kafka:29092 \
     --topic payments.money-orders.v1 \
     --property parse.key=true --property key.separator='|' <<< '<order_group_id>|<original json>'
   ```

5. **Confirm it applied.**

   ```sql
   SELECT order_id, applied_at FROM applied_orders WHERE order_id = '<order id>';
   ```

   No row means it was quarantined again — go back to step 2 and read the newest quarantine row.

6. **Mark the original row resolved.** Only after step 5 shows the order applied.

   ```sql
   UPDATE quarantined_orders SET resolved_at = now() WHERE quarantine_id = <id>;
   ```

   Resolving is an update, never a delete: the runtime role has no `DELETE` on the quarantine tables precisely so that
   evidence cannot be removed while tidying up.

### What this procedure does not do

- It does not repair a **structurally invalid** order. Money that does not balance is a defect upstream; the fix is a
  corrected order from the producer, not an edited payload.
- It does not address a **paused listener**. A pause is a transient-failure signal and resolves itself once the
  database answers (D04-4); check `ledger_listener_paused` rather than re-publishing anything.
- It has **not been executed as an automated test**. The steps are written and the SQL and CLI invocations are real,
  but the dry run is deferred ([docs/scope-decisions.md](scope-decisions.md)), so treat the first live use carefully.
