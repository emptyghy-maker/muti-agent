package com.ghy.mutiagent.service.route;

import com.ghy.mutiagent.model.RouteFact;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S10 并行结果版本闸：迟到的旧修订结果丢弃，不混入新计划。
 */
class FactVersionGateTest {

    private static RouteFact fact(String id) {
        return new RouteFact(id, "TRANSIT", 30, BigDecimal.ONE, 2.0,
                RouteFact.KIND_REALTIME, "scripted-provider", Instant.now(),
                Instant.now().plusSeconds(3600), RouteFact.CONFIDENCE_HIGH, "v1", List.of());
    }

    @Test
    void staleRevisionDiscardedAndCurrentCommitted() throws Exception {
        FactVersionGate gate = new FactVersionGate(1);
        CompletableFuture<RouteFact> weather = new CompletableFuture<>();
        CompletableFuture<RouteFact> hours = new CompletableFuture<>();
        List<String> committedFacts = new ArrayList<>();
        gate.bind(1, weather, f -> committedFacts.add(f.factId()));
        gate.bind(2, hours, f -> committedFacts.add(f.factId()));

        gate.setCurrentRevision(2);
        hours.complete(fact("hours-r2"));      // 版本一致 → 生效
        weather.complete(fact("weather-r1"));  // 迟到旧版本 → 丢弃
        // 等待 whenComplete 回调
        Thread.sleep(100);

        assertEquals(List.of(2), gate.committedRevisions());
        assertTrue(gate.discardedRevisions().contains(1));
        assertEquals(List.of("hours-r2"), committedFacts);
    }

    @Test
    void failedFutureCommitsNothing() throws Exception {
        FactVersionGate gate = new FactVersionGate(1);
        CompletableFuture<RouteFact> f = new CompletableFuture<>();
        gate.bind(1, f, ignored -> {
            throw new AssertionError("失败的结果不应生效");
        });
        f.completeExceptionally(new IllegalStateException("provider down"));
        Thread.sleep(100);
        assertTrue(gate.committedRevisions().isEmpty());
        assertTrue(gate.discardedRevisions().isEmpty());
    }

    @Test
    void currentRevisionIsSynchronized() {
        FactVersionGate gate = new FactVersionGate(0);
        gate.setCurrentRevision(5);
        assertEquals(5, gate.currentRevision());
    }
}
