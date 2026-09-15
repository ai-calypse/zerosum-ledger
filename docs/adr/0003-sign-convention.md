# ADR-0003 — Sign convention and normal sides

- **Status:** Accepted
- **Date:** 2026-09-15
- **Decision owner:** S01 (D01-6) — [docs/step_01_domain_contracts.md#decisions-and-outputs](../step_01_domain_contracts.md#decisions-and-outputs)

## Context

Every money order, ledger balance, read API and verifier check in ZeroSum Ledger needs to agree on what the sign of an amount means. The master plan proposes a signed-amount convention ([§4.7](../zerosum_ledger_mvp_plan.md#decisions), [§5.2](../zerosum_ledger_mvp_plan.md#chart-of-accounts)) and says balances are displayed on each account's normal side. It doesn't say how to present a *contra* balance, a balance on the opposite side of normal. Two examples:

- driver debt, a debit balance on the credit-normal `payable`;
- rider credit, a credit balance on the debit-normal `receivable`.

One convention must serve the validator (`libs/money`), the ledger's balances and read API (S02), and the verifier (S06). Otherwise, a sign error in any one of them silently becomes a money error.

## Decision

1. **Signed amounts.** Every entry is `(entity, account, currency, amount_minor)`, where a debit is positive and a credit is negative.
2. **Zero-sum per currency.** For every order and every currency, the entries' amounts sum to exactly 0. Currencies are never netted against each other (no FX; master A11).
3. **One normal side per account code**, keyed by account code and allowed per entity kind:

   | Account | Normal side |
   |---|---|
   | `receivable`, `cash`, `processing_fees`, `clearing` | DEBIT |
   | `payable`, `revenue`, `payout_clearing` | CREDIT |

4. **Presentation.** A balance is presented on its account's normal side. The presented value is the signed balance for debit-normal accounts, and its negation for credit-normal accounts.
5. **Contra balances present as negative amounts on the normal side.** Driver debt of 500 on `payable` (signed +500) presents as −500. A rider credit of 300 on `receivable` (signed −300) presents as −300. The sign therefore always answers "is this account on its normal side?" without a second field.

The code is the source of truth for the table above:

- [`ChartOfAccounts.java`](../../libs/money/src/main/java/dev/zerosum/money/ChartOfAccounts.java), including `presentOnNormalSide`;
- the contra case is tested in [`ChartOfAccountsTest.java`](../../libs/money/src/test/java/dev/zerosum/money/ChartOfAccountsTest.java) (`presentationFollowsAdr0003IncludingContraBalances`).

## Alternatives considered

- **Unsigned amount plus a direction flag per entry.** Rejected: summing needs a branch per entry, and a flipped flag is as easy to get wrong as a flipped sign, but harder to spot in SQL.
- **Separate debit and credit accounts per transfer** (the two-account transfer model of TigerBeetle, [§2.3](../zerosum_ledger_mvp_plan.md#products)). Rejected: multi-leg orders such as O1 and O6 would need linked chains, and a single per-currency sum couldn't express the zero-sum check.
- **Separate debit and credit columns.** Rejected: every consumer must combine two columns, and both being non-zero on one line becomes a representable invalid state.
- **Credit-positive convention.** Rejected: equally valid, but the opposite of standard accounting identities and of most published ledger designs the project borrows from ([§2.2](../zerosum_ledger_mvp_plan.md#industry-designs)).
- **Presenting contra balances as a positive amount with the opposite side label.** Rejected: every consumer would need side-aware formatting, and "negative on the normal side" is one rule everywhere.

## Consequences

- The ledger read API presents balances with `presentOnNormalSide` (D02-7), and APIs never invent a second rule.
- **Clearing accounts returning to zero is the health signal.** `clearing` and `payout_clearing` at 0 after quiesce is invariant I9 ([§8.3](../zerosum_ledger_mvp_plan.md#invariants)). Their signed and presented values are both 0 when healthy, so the check is sign-independent.
- **Driver debt appears as a negative `payable`.** It is the R1 metric ([§8.3](../zerosum_ledger_mvp_plan.md#invariants)): a payout racing a downward adjustment shows up as a presented payable below zero.
- The global per-currency sum of signed balances is 0 (I2), because every applied order sums to 0.
