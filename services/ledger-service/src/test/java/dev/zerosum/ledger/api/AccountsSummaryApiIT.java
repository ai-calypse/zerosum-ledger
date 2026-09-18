package dev.zerosum.ledger.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.zerosum.contracts.GoldenPayloads;
import dev.zerosum.ledger.support.ApplyTestDriver;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S09: {@code GET /v1/accounts/summary}, the dashboard's balances by account class.
 *
 * <p>The database is shared with the other API tests, so nothing here asserts an absolute figure: every class is checked
 * against the accounts table directly, and the per-currency totals against I2, which holds whatever else was applied.
 */
class AccountsSummaryApiIT extends LedgerApiTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeAll
    static void applyTheWorkedExample() {
        ApplyTestDriver driver = applyDriver();
        for (int i = 1; i <= 7; i++) {
            driver.applyOne(GoldenPayloads.byId("O" + i));
        }
    }

    @Test
    void everyClassEqualsTheSumOfItsAccountsAndEveryCurrencyTotalsZero() throws SQLException {
        JsonNode summary = JSON.readTree(http().get().uri("/v1/accounts/summary").retrieve().body(String.class));

        JsonNode classes = summary.get("classes");
        assertFalse(classes.isEmpty(), "the worked example leaves balances in several classes");
        for (JsonNode row : classes) {
            long[] expected = sumOf(row.get("entity_kind").asString(), row.get("account").asString(),
                    row.get("currency").asString());
            assertEquals(expected[0], row.get("accounts").asLong(), row.toString());
            assertEquals(expected[1], row.get("signed_minor").asLong(), row.toString());
            long presented = "CREDIT".equals(row.get("normal_side").asString()) ? -expected[1] : expected[1];
            assertEquals(presented, row.get("presented_minor").asLong(), "presented on the normal side: " + row);
        }

        // I2: whatever the other tests applied, every currency's classes sum to zero.
        assertFalse(summary.get("currency_totals").isEmpty());
        for (JsonNode total : summary.get("currency_totals")) {
            assertEquals(0, total.get("signed_minor").asLong(), total.toString());
        }
    }

    @Test
    void theSummaryNeedsAReaderToken() {
        int status = RestClient.create(baseUrl()).get().uri("/v1/accounts/summary")
                .exchange((request, response) -> response.getStatusCode().value(), false);
        assertEquals(401, status);
    }

    /** {accounts, signed sum} for one class, straight from the table. */
    private static long[] sumOf(String kind, String account, String currency) throws SQLException {
        try (Connection connection = DB.connect(LedgerTestDatabase.APP);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT count(*), coalesce(sum(a.balance_minor), 0)
                        FROM accounts a JOIN entities e ON e.entity_id = a.entity_id
                        WHERE e.kind = ? AND a.account_code = ? AND a.currency = ?""")) {
            statement.setString(1, kind);
            statement.setString(2, account);
            statement.setString(3, currency);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return new long[] {rs.getLong(1), rs.getLong(2)};
            }
        }
    }
}
