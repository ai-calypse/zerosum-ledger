package dev.zerosum.order.api;

import dev.zerosum.auth.Principal;
import dev.zerosum.auth.Role;
import dev.zerosum.money.ChartOfAccounts;
import dev.zerosum.money.CurrencyRules;
import dev.zerosum.money.OrderCandidate;
import dev.zerosum.money.ValidationLimits;
import dev.zerosum.money.Violation;
import dev.zerosum.money.ZeroSumValidator;
import dev.zerosum.order.order.NewOrder;
import dev.zerosum.order.order.OrderStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The money-order API (D03-2, TB1). Create is writer-only; the reads are reader-role, which writer and admin tokens
 * also satisfy (D03-4).
 *
 * <p>Nothing here touches Kafka: publication is the outbox relay's job (S03-T05), so an order is accepted even when the
 * broker is down.
 */
@RestController
class MoneyOrderController {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZeroSumValidator VALIDATOR = ZeroSumValidator.defaults();
    private static final CurrencyRules CURRENCIES = CurrencyRules.defaults();

    private final OrderStore store;

    MoneyOrderController(OrderStore store) {
        this.store = store;
    }

    @PostMapping("/v1/money-orders")
    ResponseEntity<MoneyOrderResponse> create(HttpServletRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody MoneyOrderRequest body) {
        Principal principal = ApiAuthorization.require(request, Role.WRITER);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiException.idempotencyKeyMissing();   // M3 (d)
        }
        if (idempotencyKey.length() > 255) {
            throw ApiException.validationFailed("the Idempotency-Key header is at most 255 characters");
        }

        NewOrder order = toNewOrder(principal, idempotencyKey.strip(), body);
        validate(order);   // before the idempotency lookup: a validation failure is never stored (D03-3)

        OrderStore.Result result = store.create(order);
        return switch (result.status()) {
            case CREATED -> ResponseEntity.status(HttpStatus.CREATED).body(MoneyOrderResponse.of(result.order()));
            // M3 (a): the same body as the original, plus the replay header, so a retry is indistinguishable.
            case REPLAYED -> ResponseEntity.ok().header("Idempotent-Replayed", "true")
                    .body(MoneyOrderResponse.of(result.order()));
            case KEY_REUSED -> throw ApiException.keyReused();
            case IN_PROGRESS -> throw ApiException.keyInProgress();
            case NOT_ZERO_SUM -> throw ApiException.notZeroSum(
                    "entries must sum to zero per currency, with at least two entries");
        };
    }

    @GetMapping("/v1/money-orders/{orderId}")
    MoneyOrderResponse fetch(HttpServletRequest request, @PathVariable String orderId) {
        ApiAuthorization.require(request, Role.READER);
        UUID id;
        try {
            id = UUID.fromString(orderId);
        } catch (IllegalArgumentException malformed) {
            throw ApiException.notFound();
        }
        return store.read(id).map(MoneyOrderResponse::of).orElseThrow(ApiException::notFound);
    }

    record TypeTotal(String type, String reason, String currency, long orders,
            @com.fasterxml.jackson.annotation.JsonProperty("volume_minor") long volumeMinor,
            @com.fasterxml.jackson.annotation.JsonProperty("unbalanced_orders") long unbalancedOrders) {
    }

    record LegTotal(String type, String reason,
            @com.fasterxml.jackson.annotation.JsonProperty("entity_kind") String entityKind, String account,
            String currency, long entries,
            @com.fasterxml.jackson.annotation.JsonProperty("signed_minor") long signedMinor) {
    }

    record OrdersSummary(List<TypeTotal> types, List<LegTotal> legs) {
    }

    /** Totals by order type and the money flow between account classes, for the S09 dashboard. Reader role. */
    @GetMapping("/v1/money-orders/summary")
    OrdersSummary summary(HttpServletRequest request) {
        ApiAuthorization.require(request, Role.READER);
        OrderStore.Summary summary = store.summary();
        return new OrdersSummary(
                summary.types().stream().map(t -> new TypeTotal(t.type(), t.reason(), t.currency(), t.orders(),
                        t.volumeMinor(), t.unbalancedOrders())).toList(),
                summary.legs().stream().map(l -> new LegTotal(l.type(), l.reason(), l.entityKind(), l.account(),
                        l.currency(), l.entries(), l.signedMinor())).toList());
    }

    @GetMapping("/v1/money-orders")
    List<MoneyOrderResponse> listByGroup(HttpServletRequest request, @RequestParam("group_id") String groupId) {
        ApiAuthorization.require(request, Role.READER);
        return store.readByGroup(groupId).stream().map(MoneyOrderResponse::of).toList();
    }

    private static NewOrder toNewOrder(Principal principal, String idempotencyKey, MoneyOrderRequest body) {
        if (body == null) {
            throw ApiException.validationFailed("a request body is required");
        }
        List<OrderCandidate.Entry> entries = new ArrayList<>();
        for (MoneyOrderRequest.EntryRequest entry : body.entries() == null ? List.<MoneyOrderRequest.EntryRequest>of()
                : body.entries()) {
            if (entry.amountMinor() == null) {
                throw ApiException.validationFailed("every entry needs an integer amount_minor");
            }
            entries.add(OrderCandidate.Entry.of(entry.entityId(), entry.account(), entry.currency(),
                    entry.amountMinor()));
        }
        UUID adjusts = null;
        if (body.adjustsOrderId() != null) {
            try {
                adjusts = UUID.fromString(body.adjustsOrderId());
            } catch (IllegalArgumentException malformed) {
                throw ApiException.validationFailed("adjusts_order_id must be a UUID");
            }
        }
        Instant effectiveAt;
        try {
            effectiveAt = Instant.parse(body.effectiveAt());
        } catch (DateTimeParseException | NullPointerException bad) {
            throw ApiException.validationFailed("effective_at must be an RFC 3339 timestamp ending in Z");
        }
        String metadataJson = body.metadata() == null ? null : JSON.writeValueAsString(body.metadata());

        // source.system comes from the principal, never the body (D01-5 rule 8).
        return new NewOrder(principal.sourceSystem(), idempotencyKey, body.type(), body.reason(), body.orderGroupId(),
                adjusts, entries, metadataJson, effectiveAt);
    }

    /**
     * The D03-3 validation order, run before any database work so a rejected request is never stored and a corrected
     * retry with the same key still succeeds (Stripe semantics, master standards).
     */
    private static void validate(NewOrder order) {
        if (order.orderGroupId() == null || order.orderGroupId().isBlank()) {
            throw ApiException.validationFailed("order_group_id is required");
        }
        // Rule 7: the API accepts COMMERCE only. Every other type comes from the internal mapper (S03-T07).
        if (!"COMMERCE".equals(order.type())) {
            throw ApiException.validationFailed("this endpoint accepts COMMERCE orders only");
        }
        if (order.entries().size() < ValidationLimits.MIN_ENTRIES
                || order.entries().size() > ValidationLimits.MAX_ENTRIES) {
            throw ApiException.validationFailed("an order carries between " + ValidationLimits.MIN_ENTRIES + " and "
                    + ValidationLimits.MAX_ENTRIES + " entries");
        }
        for (OrderCandidate.Entry entry : order.entries()) {
            if (ChartOfAccounts.kindOf(entry.entityId()).isEmpty()) {
                throw ApiException.validationFailed("entity_id must match " + ValidationLimits.ENTITY_ID_PATTERN.pattern());
            }
            if (!CURRENCIES.isAllowed(entry.currency())) {
                throw ApiException.validationFailed("currency " + entry.currency() + " is not on the allow-list");
            }
        }
        List<Violation> violations = VALIDATOR.validate(order.asCandidate());
        if (!violations.isEmpty()) {
            Violation first = violations.get(0);
            // Only a genuine imbalance is not_zero_sum. An overflow is a representation limit, so it is a validation
            // problem rather than a claim about the arithmetic (D01-1), and never a 500.
            if (first.code() == Violation.Code.ZERO_SUM_VIOLATED) {
                throw ApiException.notZeroSum("entries must sum to zero per currency; currency " + first.currency());
            }
            if (first.code() == Violation.Code.ZERO_SUM_OVERFLOW) {
                throw ApiException.validationFailed(
                        "the per-currency sum overflows a 64-bit amount; currency " + first.currency());
            }
            throw ApiException.validationFailed("rule " + first.rule() + " failed: " + first.code());
        }
    }
}
