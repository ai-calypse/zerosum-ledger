package dev.zerosum.ledger.apply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.zerosum.auth.ChaosGuard;
import dev.zerosum.contracts.GoldenPayloads;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** D08-3: A4's ledger layer (§0.3 E8) removes the zero-sum re-check and nothing else. */
class OrderDecoderAblationTest {

    private static final OrderDecoder A4 = new OrderDecoder(new ChaosGuard.Active("ledger-service", List.of("A4")));

    /** O1 with the rider leg one minor unit high: A4's ±1 fare-split bug. */
    private static byte[] offByOne() {
        return GoldenPayloads.byId("O1").replace("\"amount_minor\": 2500", "\"amount_minor\": 2501")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void theReCheckQuarantinesAnUnbalancedOrder() {
        var rejected = assertInstanceOf(OrderDecoder.Result.Rejected.class, new OrderDecoder().decode(offByOne()));
        assertEquals(QuarantineCode.STRUCTURALLY_INVALID, rejected.code());
    }

    @Test
    void underA4TheUnbalancedOrderDecodesButOtherLedgerRulesStillHold() {
        assertInstanceOf(OrderDecoder.Result.Decoded.class, A4.decode(offByOne()));
        // Rule 3 (a rider may not hold a payable account) is not part of A4.
        byte[] wrongAccount = GoldenPayloads.byId("O1").replace("\"account\": \"receivable\"", "\"account\": \"payable\"")
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(QuarantineCode.STRUCTURALLY_INVALID,
                assertInstanceOf(OrderDecoder.Result.Rejected.class, A4.decode(wrongAccount)).code());
    }
}
