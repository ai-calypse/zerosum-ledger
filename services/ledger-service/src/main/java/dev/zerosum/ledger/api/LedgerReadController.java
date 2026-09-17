package dev.zerosum.ledger.api;

import dev.zerosum.ledger.api.LedgerReadResponses.AccountBalance;
import dev.zerosum.ledger.api.LedgerReadResponses.Balances;
import dev.zerosum.ledger.api.LedgerReadResponses.ChangelogPage;
import dev.zerosum.ledger.api.LedgerReadResponses.ChangelogRow;
import dev.zerosum.ledger.api.LedgerReadResponses.ChangelogSource;
import dev.zerosum.auth.Role;
import dev.zerosum.auth.TokenAuthFilter;
import dev.zerosum.ledger.store.LedgerStore;
import dev.zerosum.ledger.store.LedgerStore.BalancesSnapshot;
import dev.zerosum.ledger.store.LedgerStore.ChangelogPageRow;
import dev.zerosum.money.ChartOfAccounts;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reader endpoints for balances and the changelog (D02-7). Both are read-only and take no lock: balances come from one
 * snapshot statement, and changelog paging walks the append-only {@code (entity_id, seq)} primary key.
 *
 * <p>Token checks are deliberately absent. S03-T03 wires the shared {@code libs/auth} module into ledger-service
 * (master §0.3 C9); until then the service port stays local-only (D00-3).
 */
@RestController
class LedgerReadController {

    private final LedgerStore store;
    private final LedgerApiProperties properties;

    LedgerReadController(LedgerStore store, LedgerApiProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @GetMapping("/v1/entities/{entityId}/balances")
    Balances balances(HttpServletRequest request, @PathVariable String entityId) {
        LedgerAuthorization.requireReader(request);
        String id = EntityIds.validated(entityId);
        BalancesSnapshot snapshot = store.readBalancesSnapshot(id).orElseThrow(() -> LedgerApiException.entityNotFound(id));
        List<AccountBalance> accounts = snapshot.accounts().stream()
                .map(a -> new AccountBalance(a.accountCode(), a.currency(),
                        ChartOfAccounts.normalSide(a.accountCode()).name(),
                        ChartOfAccounts.presentOnNormalSide(a.accountCode(), a.signedMinor()), a.signedMinor()))
                .toList();
        return new Balances(snapshot.entityId(), snapshot.kind(), snapshot.asOfSeq(), accounts);
    }

    @GetMapping("/v1/entities/{entityId}/changelog")
    ChangelogPage changelog(HttpServletRequest request, @PathVariable String entityId,
            @RequestParam(name = "after_seq", required = false) Long afterSeq,
            @RequestParam(name = "limit", required = false) Integer limit) {
        LedgerAuthorization.requireReader(request);
        String id = EntityIds.validated(entityId);
        long cursor = afterSeq == null ? 0 : afterSeq;
        if (cursor < 0) {
            throw LedgerApiException.invalidCursor(afterSeq);
        }
        int size = limit == null ? properties.defaultPageSize() : limit;
        if (size < 1 || size > properties.maxPageSize()) {
            throw LedgerApiException.invalidLimit(limit, properties.maxPageSize());
        }
        // An unknown entity is a 404 here as well as on balances, so both operations answer the same way (D02-7).
        if (store.readBalancesSnapshot(id).isEmpty()) {
            throw LedgerApiException.entityNotFound(id);
        }

        List<ChangelogPageRow> rows = store.readChangelogPage(id, cursor, size);
        List<ChangelogRow> body = rows.stream()
                .map(r -> new ChangelogRow(r.seq(), r.orderId(), r.accountCode(), r.currency(), r.deltaMinor(),
                        r.balanceAfterMinor(), r.recordedAt(), new ChangelogSource(r.sourceSystem(), r.idempotencyKey())))
                .toList();
        // A full page may still be the last one; the next request then returns an empty page, never a 404.
        Long next = body.size() < size ? null : body.get(body.size() - 1).seq();
        return new ChangelogPage(id, body, next);
    }

}
