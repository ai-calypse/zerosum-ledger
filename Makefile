# ZeroSum Ledger — one place for the commands you actually run.
#
# Every target here is a command that has been executed against this repository, not an aspiration. Where something
# has never been measured (the M14(a) fresh-clone timing, for instance), no target claims a number.

COMPOSE := docker compose -f docker-compose.yml -f docker-compose.demo.yml
LEDGER  := http://127.0.0.1:8082
ORDERS  := http://127.0.0.1:8081
PROVIDERS := http://127.0.0.1:8090
GRAFANA := http://127.0.0.1:3000

.DEFAULT_GOAL := help
.PHONY: help env build up down ps logs test integration e2e explorer grafana demo providers verify-stack

help: ## Show this help
	@grep -E '^[a-z-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

env: ## Generate .env with local throwaway secrets (required once)
	tools/dev/generate-env.sh

build: ## Build service jars and the OpenTelemetry agent
	./gradlew assemble

up: build ## Start the whole stack, including the reachable fake providers
	$(COMPOSE) up -d --build --wait
	@$(MAKE) --no-print-directory ps

down: ## Stop everything and delete all data
	$(COMPOSE) down -v

ps: ## Show container health
	@$(COMPOSE) ps --format "table {{.Service}}\t{{.Status}}"

logs: ## Tail logs from every service
	$(COMPOSE) logs -f --tail=100

test: ## Unit layer: compile and fast tests, no containers
	./gradlew build

integration: ## Integration layer: Testcontainers (needs Docker, no stack)
	./gradlew integrationTest

e2e: ## End-to-end money path against the RUNNING stack (run `make up` first)
	./gradlew e2eTest

explorer: ## Open the Ledger Explorer
	@echo "$(LEDGER)/explorer.html — the reader token is ZS_READER_TOKEN in .env"
	@command -v open >/dev/null && open "$(LEDGER)/explorer.html" || true

grafana: ## Open Grafana (flow and money-invariants dashboards)
	@command -v open >/dev/null && open "$(GRAFANA)" || true

providers: ## Drive the simulated providers directly: a charge, a decline, and a payout that is returned
	@echo "== FakeCard: a charge that succeeds (fee is 290bps + 30, per D01-3) =="
	@curl -s -X POST $(PROVIDERS)/fakecard/v1/charges -H 'Content-Type: application/json' \
	  -d '{"client_reference":"demo-ok","instrument_token":"tok_card_ok","amount_minor":10000,"currency":"USD"}'
	@echo "\n\n== FakeCard: a decline, which collects no fee =="
	@curl -s -X POST $(PROVIDERS)/fakecard/v1/charges -H 'Content-Type: application/json' \
	  -d '{"client_reference":"demo-declined","instrument_token":"tok_card_decline_insufficient_funds","amount_minor":500,"currency":"USD"}'
	@echo "\n\n== FakeBank: a payout accepted with 202, which will settle and then be returned (R01) =="
	@curl -s -X POST $(PROVIDERS)/fakebank/v1/payouts -H 'Content-Type: application/json' \
	  -d '{"client_reference":"demo-return","destination_token":"tok_bank_return_R01","amount_minor":25000,"currency":"USD"}'
	@echo "\n\nWatch it move (a simulated banking day is 30s by default):"
	@echo "  curl -s '$(PROVIDERS)/fakebank/v1/payouts?client_reference=demo-return'"

verify-stack: ## Ask the running ledger whether its own books are consistent
	@test -f .env || { echo "run 'make env' first"; exit 1; }
	@TOKEN=$$(grep '^ZS_READER_TOKEN=' .env | cut -d= -f2-); \
	  curl -s -H "Authorization: Bearer $$TOKEN" $(LEDGER)/v1/invariants; echo; \
	  curl -s -H "Authorization: Bearer $$TOKEN" $(LEDGER)/v1/freshness; echo

demo: up ## Full walkthrough: start the stack, move real money through it, then show the books
	@echo "\n=== 1. the money path, end to end (order API -> outbox -> Kafka -> ledger) ==="
	./gradlew e2eTest
	@echo "\n=== 2. the simulated providers ==="
	@$(MAKE) --no-print-directory providers
	@echo "\n\n=== 3. the ledger's own invariants ==="
	@$(MAKE) --no-print-directory verify-stack
	@echo "\n=== 4. look at it ==="
	@echo "  Explorer:  $(LEDGER)/explorer.html"
	@echo "  Grafana:   $(GRAFANA)"
	@echo "  Orders:    $(ORDERS)/v1/money-orders  (writer token required)"
