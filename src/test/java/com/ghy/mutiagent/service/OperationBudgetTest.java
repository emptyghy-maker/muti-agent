package com.ghy.mutiagent.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S08 共享预算：原子预留、实际结算、未知用量挂账、调用配额与截止。
 */
class OperationBudgetTest {

    /** 可控时钟：ms 粒度（1ms = 1_000_000ns），测试按 ms 推进 */
    private static final class ControlledTime implements TimeSource {
        long nowMs;

        @Override
        public long nanoTime() {
            return nowMs * 1_000_000L;
        }
    }

    private static OperationBudget budget(ControlledTime t, long tokenBudget, int maxCalls, long deadlineMs) {
        return new OperationBudget(t,
                new OperationBudgetConfig(2, maxCalls, tokenBudget, 100, deadlineMs, 3));
    }

    @Test
    void reserveThenSettleActualUsage() {
        OperationBudget b = budget(new ControlledTime(), 1000, 3, 10000);
        OperationBudget.Reserve r = b.reserveCall(200); // 200 + maxOutput100 = 300
        assertEquals(700, b.remainingTokens());
        b.settle(r, 250L);
        assertEquals(750, b.remainingTokens());
    }

    @Test
    void unknownUsageKeepsReservation() {
        OperationBudget b = budget(new ControlledTime(), 1000, 3, 10000);
        OperationBudget.Reserve r = b.reserveCall(200);
        b.settle(r, null); // 计量未知：预留不得释放（不按零扣除）
        assertEquals(700, b.remainingTokens());
    }

    @Test
    void insufficientTokensRefusesCallAndRecordsReason() {
        OperationBudget b = budget(new ControlledTime(), 500, 3, 10000);
        OperationBudget.Reserve r1 = b.reserveCall(100);
        b.settle(r1, 350L); // 结算后余 150
        OperationBudget.Reserve r2 = b.reserveCall(100); // 需 200 > 150
        assertNull(r2);
        assertTrue(b.reasonCodes().contains(OperationBudget.REASON_TOKEN_BUDGET));
        assertEquals(1, b.callsStarted());
    }

    @Test
    void callQuotaSharedAcrossRoles() {
        OperationBudget b = budget(new ControlledTime(), 100000, 3, 10000);
        assertEquals(3, b.config().maxModelCalls());
        b.reserveCall(0);
        b.reserveCall(0);
        b.reserveCall(0);
        assertNull(b.reserveCall(0));
        assertTrue(b.reasonCodes().contains(OperationBudget.REASON_MODEL_CALLS));
    }

    @Test
    void deadlineBlocksNewCallsAndIsMonotonic() {
        ControlledTime t = new ControlledTime();
        OperationBudget b = budget(t, 100000, 3, 1000);
        assertFalse(b.deadlineExceeded());
        t.nowMs = 999;
        assertFalse(b.deadlineExceeded());
        t.nowMs = 1001;
        assertTrue(b.deadlineExceeded());
        assertNull(b.reserveCall(0));
        assertTrue(b.reasonCodes().contains(OperationBudget.REASON_DEADLINE));
    }

    @Test
    void reasonCodesAreSnapshotCopies() {
        OperationBudget b = budget(new ControlledTime(), 10, 3, 10000);
        b.reserveCall(100);
        List<String> codes = b.reasonCodes();
        codes.add("tampered");
        assertFalse(b.reasonCodes().contains("tampered"));
    }
}
