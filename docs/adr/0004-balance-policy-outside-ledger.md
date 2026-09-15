# ADR-0004 — Balance-dependent policy lives outside the ledger

- **Status:** Accepted (ledger half; the policy half is added by S05)
- **Date:** 2026-09-15
- **Decision owner:** S02 (D02-12) — [docs/step_02_ledger_core.md#decisions-and-outputs](../step_02_ledger_core.md#decisions-and-outputs). The instrument-service half belongs to S05 (D05-7).

## Context

Two different questions get confused in payment systems:

1. *Is this money movement structurally valid?* — do the entries balance per currency, and may this account hold this entry?
2. *Should we move this money now?* — does the driver have enough owed to be paid out, is a payout already in flight, are balances fresh enough?

If the ledger answered the second question, every writer would need to know policy, and the ledger could reject an order **after** it was published — leaving the order store and the ledger permanently disagreeing, with no way for the publisher to react. Uber's model, and the master plan ([§4.7](../zerosum_ledger_mvp_plan.md#decisions)), keep the two apart.

## Decision

**The ledger apply engine rejects an order only when it is structurally invalid**, that is: the payload cannot be decoded, it fails the money-order schema, an account is not allowed for its entity kind, or the entries do not sum to zero per currency ([D01-5](../step_01_domain_contracts.md#decisions-and-outputs) `RuleSet.LEDGER`). Such a record is quarantined, never applied.

**The ledger never rejects an order for a balance reason.** In particular:

- a balance may go negative, in either direction, on any account;
- a driver `payable` in debit is driver debt, which is a real and expected state ([master §5.6 policies](../zerosum_ledger_mvp_plan.md#rest-apis));
- there is no minimum balance, no credit limit and no reservation check in apply.

**Balance-dependent policy lives in instrument-service** (S05): payout eligibility, the minimum payout amount, one in-flight payout per driver and currency, and the pipeline-freshness check against the balances API.

The only balance-related failure apply can raise is **arithmetic overflow** of a `long` balance or sequence number ([D01-1](../step_01_domain_contracts.md#decisions-and-outputs)). That is not a policy decision: it means the value cannot be represented. The record is isolated and quarantined with `ARITHMETIC_OVERFLOW`.

## Consequences

- **Apply is total for valid orders.** Once order-service publishes a valid order, the ledger will apply it; the two stores converge (I6, I6b).
- **Negative balances are a reporting concern, not an error.** The R1 metric counts drivers in debt ([§8.3](../zerosum_ledger_mvp_plan.md#invariants)), and ADR-0003 presents a contra balance as a negative amount on the normal side.
- **A payout racing a downward adjustment can overdraw a driver.** That is accepted and measured in the chaos runs, rather than prevented by a ledger-side check that could not see in-flight work anyway.
- **Policy needs fresh balances.** Because the ledger will not refuse, instrument-service must check freshness itself before a payout run (M10, D05-7).
- Tested by `NegativeBalanceIT` (a payout drives a payable into debit and is applied) and `ApplyOverflowIT` (overflow is isolated and quarantined, and the rest of the batch still applies).
