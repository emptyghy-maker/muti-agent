package com.ghy.mutiagent.context.baseline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.observability.collection.ObsEventSink;
import com.ghy.mutiagent.observability.collection.ObsInstrumentation;
import com.ghy.mutiagent.observability.domain.ObsEvent;
import com.ghy.mutiagent.observability.domain.TraceEnvelope;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ContextBaselineRecorderTest {

    @Test
    void stableHashIgnoresMapInsertionOrderButKeepsArrayOrder() {
        StableContextHasher hasher = new StableContextHasher(new ObjectMapper());
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("b", 2);
        first.put("a", 1);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", 1);
        second.put("b", 2);

        assertEquals(hasher.hash(first), hasher.hash(second));
        assertNotEquals(hasher.hash(java.util.List.of(1, 2)), hasher.hash(java.util.List.of(2, 1)));
    }

    @Test
    void recordsMetadataWithoutRawPayloadAndKeepsProviderUsage() {
        AtomicReference<ObsEvent> captured = new AtomicReference<>();
        ObsInstrumentation instrumentation = new ObsInstrumentation(new CapturingSink(captured));
        ContextBaselineProperties properties = new ContextBaselineProperties();
        properties.setEnabled(true);
        properties.setLogEnabled(false);
        DefaultContextBaselineRecorder recorder = new DefaultContextBaselineRecorder(
                properties, new ObjectMapper(), instrumentation);

        ContextObservation observation = recorder.begin(new ContextCaptureRequest(
                "s1", 7L, "turn-1", null, "PreferenceAgent", "PREFERENCE",
                "preference-context-v1", 3, 4, 0,
                Map.of(ContextSectionNames.CURRENT_TURN, "联系 test@example.com，预算600",
                        ContextSectionNames.PREFERENCE, Map.of("days", 1))));
        recorder.providerFinished(observation, new TokenUsage(12, 4), 25, "SUCCESS");
        recorder.parseFinished(observation, "SUCCESS", "NOT_APPLICABLE", null);

        ObsEvent event = captured.get();
        assertNotNull(event);
        assertEquals("PreferenceAgent", event.summary().get("agentName"));
        assertEquals(12, event.summary().get("providerInputTokens"));
        String serialized = event.summary().toString();
        assertFalse(serialized.contains("test@example.com"));
        assertFalse(serialized.contains("预算600"));
        assertTrue(serialized.contains("CURRENT_TURN"));
        assertTrue(serialized.contains("sensitive=true"));
    }

    @Test
    void disabledRecorderEmitsNothing() {
        AtomicReference<ObsEvent> captured = new AtomicReference<>();
        ContextBaselineProperties properties = new ContextBaselineProperties();
        properties.setEnabled(false);
        DefaultContextBaselineRecorder recorder = new DefaultContextBaselineRecorder(
                properties, new ObjectMapper(), new ObsInstrumentation(new CapturingSink(captured)));

        ContextObservation observation = recorder.begin(new ContextCaptureRequest(
                "s1", 1L, null, null, "FoodAgent", "FOODS", "v1",
                0, 0, 0, Map.of("PREFERENCE", "x")));
        recorder.providerFinished(observation, new TokenUsage(1, 1), 1, "SUCCESS");
        recorder.parseFinished(observation, "SUCCESS", "SUCCESS", null);

        assertNull(captured.get());
    }

    @Test
    void observationDoesNotRetainCaptureRequestWithRawSections() {
        ContextBaselineProperties properties = new ContextBaselineProperties();
        properties.setEnabled(true);
        properties.setLogEnabled(false);
        DefaultContextBaselineRecorder recorder = new DefaultContextBaselineRecorder(properties,
                new ObjectMapper(), new ObsInstrumentation(new CapturingSink(new AtomicReference<>())));

        ContextObservation observation = recorder.begin(new ContextCaptureRequest(
                "s1", 1L, "turn", "op", "Agent", "STAGE", "v1",
                1, 1, 1, Map.of("large", "x".repeat(100_000))));

        assertTrue(observation.enabled());
        assertTrue(java.util.Arrays.stream(ContextObservation.class.getDeclaredFields())
                .noneMatch(field -> field.getType().equals(ContextCaptureRequest.class)));
        assertEquals("s1", observation.identity().sessionId());
    }

    private record CapturingSink(AtomicReference<ObsEvent> captured) implements ObsEventSink {
        @Override public void onSpan(TraceEnvelope envelope) { }
        @Override public void onFinished(TraceEnvelope envelope) { }
        @Override public void record(ObsEvent event) { captured.set(event); }
        @Override public String storePayload(String runId, Long ownerId, String kind, String raw) { return null; }
    }
}
