# ZeroSum Ledger

A zero-sum, immutable payments ledger modeled on the principles Uber published for its payments platform: immutable money orders whose entries sum to zero per currency, a ledger with an entity changelog, Kafka between services, and a pluggable payment-instrument interface exercised by two fake providers (FakeCard and FakeBank). It is a learning and portfolio project; no real money, cards or bank accounts are involved.

**Status:** under construction. Implementation follows the documentation pack step by step; nothing below the planning documents is complete until its step register records evidence.

- Documentation pack index: [docs/README.md](docs/README.md)
- MVP engineering report (master plan): [docs/zerosum_ledger_mvp_plan.md](docs/zerosum_ledger_mvp_plan.md)
