package com.ghy.mutiagent.observability;

import com.ghy.mutiagent.observability.application.ObsExportService;
import com.ghy.mutiagent.observability.collection.DefaultObsRecorder;
import com.ghy.mutiagent.observability.collection.EventBuffer;
import com.ghy.mutiagent.observability.collection.ObsPayloadStore;
import com.ghy.mutiagent.observability.domain.ObsEventTypes;
import com.ghy.mutiagent.observability.domain.TraceEnvelope;
import com.ghy.mutiagent.observability.security.ObsScope;
import com.ghy.mutiagent.service.TimeSource;
import com.ghy.mutiagent.trace.AgentTrace;
import com.ghy.mutiagent.trace.TraceContext;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 记录器/Scope/导出防护/游标：无 DB 的核心语义单测 */
class ObservabilityCoreTest {

    private static final TimeSource CLOCK = new TimeSource() {
        long now = 1_000_000_000L;

        @Override
        public long nanoTime() {
            return now * 1_000_000L;
        }
    };

    @Test
    void recorderEmitsSpanStartFinishOperationAndAttemptOnce() {
        EventBuffer buffer = new EventBuffer(100);
        DefaultObsRecorder recorder = new DefaultObsRecorder(buffer, new ObsPayloadStore() {
            @Override
            public String save(String runId, Long ownerId, String kind, String content,
                               String status, int sizeBytes, String digest) {
                return "p1";
            }
        }, "trace-service", 1024);

        TraceContext ctx = new TraceContext("s1", "问题", CLOCK);
        ctx.setSpanId("op-1");
        ctx.setOperationId("op-1");
        ctx.setOwnerId(7L);
        ctx.setKind("OPERATION");
        ctx.setModelVersion("stub-v1");
        recorder.onSpan(TraceEnvelope.of(ctx));
        ctx.add(AgentTrace.success("ItineraryAgent", 100, new TokenUsage(10, 20)));
        ctx.finish("SUCCESS");
        recorder.onFinished(TraceEnvelope.of(ctx));

        List<com.ghy.mutiagent.observability.domain.ObsEvent> events = buffer.drain(100);
        long spanStarted = events.stream()
                .filter(e -> ObsEventTypes.SPAN_STARTED.equals(e.eventType())).count();
        long spanFinished = events.stream()
                .filter(e -> ObsEventTypes.SPAN_FINISHED.equals(e.eventType())).count();
        long opFinished = events.stream()
                .filter(e -> ObsEventTypes.OPERATION_FINISHED.equals(e.eventType())).count();
        long attempts = events.stream()
                .filter(e -> ObsEventTypes.USAGE_ATTEMPT.equals(e.eventType())).count();
        assertEquals(1, spanStarted);
        assertEquals(1, spanFinished);
        assertEquals(1, opFinished);
        assertEquals(1, attempts); // register+finish 不双计
        assertEquals("p1", recorder.storePayload("op-1", 7L, "INPUT", "原文 ABCDEF0123456789"));
    }

    @Test
    void scopeIsolatesNullOwnerAndNonOwners() {
        ObsScope user = ObsScope.of(1L, "USER");
        ObsScope admin = ObsScope.of(9L, "ADMIN");
        assertTrue(user.canSee(1L));
        assertFalse(user.canSee(2L));
        assertFalse(user.canSee(null)); // 隔离域
        assertFalse(admin.canSee(null)); // 隔离域对 ADMIN 也不可见
        assertTrue(admin.canSee(2L));
        assertFalse(user.canManage());
        assertTrue(admin.canManage());
    }

    @Test
    void csvFormulaPrefixGuarded() {
        assertEquals("'=1+1", ObsExportService.csvSafe("=1+1"));
        assertEquals("'-cmd", ObsExportService.csvSafe("-cmd"));
        assertEquals("'@x", ObsExportService.csvSafe("@x"));
        assertEquals("'+x", ObsExportService.csvSafe("+x"));
        assertEquals("普通", ObsExportService.csvSafe("普通"));
        assertEquals("", ObsExportService.csvSafe(null));
    }

    @Test
    void instrumentationToolEventCarriesOutcome() {
        java.util.List<com.ghy.mutiagent.observability.domain.ObsEvent> captured =
                new java.util.ArrayList<>();
        com.ghy.mutiagent.observability.collection.ObsEventSink sink =
                new com.ghy.mutiagent.observability.collection.ObsEventSink() {
                    @Override
                    public void onSpan(TraceEnvelope envelope) {
                    }

                    @Override
                    public void onFinished(TraceEnvelope envelope) {
                    }

                    @Override
                    public void record(com.ghy.mutiagent.observability.domain.ObsEvent event) {
                        captured.add(event);
                    }

                    @Override
                    public String storePayload(String runId, Long ownerId, String kind, String raw) {
                        return null;
                    }
                };
        com.ghy.mutiagent.observability.collection.ObsInstrumentation instrumentation =
                new com.ghy.mutiagent.observability.collection.ObsInstrumentation(sink);
        instrumentation.toolEvent("op-1", "s1", 7L, "ROUTE", "CACHE_HIT", false,
                java.util.Map.of("factId", "f1"));
        assertEquals(1, captured.size());
        assertEquals("CACHE_HIT", captured.get(0).summary().get("outcome"));
        assertEquals("ROUTE", captured.get(0).summary().get("tool"));
        assertEquals(Boolean.FALSE, captured.get(0).summary().get("external"));
    }

    @Test
    void recorderSwallowsSinkFailures() {
        EventBuffer buffer = new EventBuffer(2);
        DefaultObsRecorder recorder = new DefaultObsRecorder(buffer, null, "p", 100);
        for (int i = 0; i < 10; i++) {
            recorder.record(new com.ghy.mutiagent.observability.domain.ObsEvent(
                    "v1", "e" + i, "r", null, null, null, null,
                    ObsEventTypes.NODE_STARTED, 1L, 1L, "p", i, 1L, "d", null, java.util.Map.of()));
        }
        assertTrue(buffer.gapCount() > 0);
        assertEquals(0, recorder.failureCount());
    }
}
