package com.ghy.mutiagent.trace;

import com.ghy.mutiagent.service.metrics.MetricsCalculator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 终态事件日志：eventId 幂等去重、重启恢复后时长一致、逻辑操作不重复计 */
class TerminalEventLogTest {

    @Test
    void duplicateDeliveryPersistsOnce() {
        TerminalEventLog.TerminalEventStorage storage = new TerminalEventLog.InMemoryStorage();
        TerminalEventLog log = new TerminalEventLog(storage);
        TerminalEventLog.TerminalEventRecord record = new TerminalEventLog.TerminalEventRecord(
                "terminal-e1", "op-1", "COMMITTED", 1234L, "{\"days\":1}");

        assertTrue(log.append(record));
        assertFalse(log.append(record));
        assertEquals(1, log.persistedCount());
    }

    @Test
    void restartReloadsSingleRecordWithSameDuration() {
        TerminalEventLog.TerminalEventStorage storage = new TerminalEventLog.InMemoryStorage();
        long durationBeforeRestart = 5678L;
        new TerminalEventLog(storage).append(new TerminalEventLog.TerminalEventRecord(
                "terminal-e1", "op-1", "COMMITTED", durationBeforeRestart, "p"));

        // 真实重启语义：新实例只依赖持久化存储
        TerminalEventLog restarted = new TerminalEventLog(storage);
        List<TerminalEventLog.TerminalEventRecord> reloaded = restarted.reload();

        assertEquals(1, restarted.persistedCount());
        long durationAfterRestart = reloaded.get(0).durationMs();
        assertEquals(durationBeforeRestart, durationAfterRestart);
    }

    @Test
    void reloadedEventsCountAsOneLogicalOperation() {
        TerminalEventLog.TerminalEventStorage storage = new TerminalEventLog.InMemoryStorage();
        TerminalEventLog log = new TerminalEventLog(storage);
        log.append(new TerminalEventLog.TerminalEventRecord("terminal-e1", "op-1", "COMMITTED", 100L, "p"));
        log.append(new TerminalEventLog.TerminalEventRecord("terminal-e1", "op-1", "COMMITTED", 100L, "p"));

        TerminalEventLog restarted = new TerminalEventLog(storage);
        List<MetricsCalculator.LogicalOperationEvent> operations = restarted.reload().stream()
                .map(r -> new MetricsCalculator.LogicalOperationEvent(r.operationId(), r.status(), false))
                .toList();
        MetricsCalculator.OperationMetrics metrics = MetricsCalculator.operations(operations);

        assertEquals(1, metrics.operationCount());
        assertEquals(1, metrics.successCount());
    }
}
