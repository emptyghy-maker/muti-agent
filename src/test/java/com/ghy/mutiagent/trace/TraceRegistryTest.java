package com.ghy.mutiagent.trace;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * S12 span 注册表：跨线程关联（spanId/operationId/attemptId/版本快照）；
 * 重试共享 operation 但 attemptId 递增；attempt 按序号稳定排序。
 */
class TraceRegistryTest {

    private static TraceContext attempt(TraceRegistry reg, String opId, int attemptNo, Long owner) {
        TraceContext ctx = new TraceContext("s", "q");
        ctx.setSpanId(opId + "-a" + attemptNo);
        ctx.setParentSpanId(opId);
        ctx.setOperationId(opId);
        ctx.setOwnerId(owner);
        ctx.setProviderAttemptId(attemptNo);
        ctx.setKind("PROVIDER");
        ctx.setPromptVersion("prompt-v2");
        reg.register(ctx);
        return ctx;
    }

    @Test
    void attemptsShareOperationWithIncrementingAttemptIds() {
        TraceRegistry reg = new TraceRegistry();
        assertEquals(1, reg.nextAttemptId("op-1"));
        assertEquals(2, reg.nextAttemptId("op-1"));
        assertEquals(1, reg.nextAttemptId("op-2"));

        attempt(reg, "op-1", 2, 1L);
        attempt(reg, "op-1", 1, 1L);
        attempt(reg, "op-2", 1, 2L);

        List<TraceContext> attempts = reg.attemptsOf("op-1");
        assertEquals(2, attempts.size());
        assertEquals(List.of(1, 2), attempts.stream()
                .map(TraceContext::getProviderAttemptId).collect(Collectors.toList()));
        assertEquals("prompt-v2", attempts.get(0).getPromptVersion());
        assertEquals("op-1", attempts.get(0).getParentSpanId());
        assertEquals(3, reg.spans().size());
    }
}
