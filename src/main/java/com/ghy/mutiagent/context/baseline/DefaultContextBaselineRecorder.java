package com.ghy.mutiagent.context.baseline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.observability.collection.ObsInstrumentation;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 默认实现：仅记录元数据到现有 Observability，并可输出脱敏结构化日志。 */
@Component
public class DefaultContextBaselineRecorder implements ContextBaselineRecorder {

    private static final Logger log = LoggerFactory.getLogger(DefaultContextBaselineRecorder.class);

    private final ContextBaselineProperties properties;
    private final ObjectMapper objectMapper;
    private final StableContextHasher hasher;
    private final ObsInstrumentation instrumentation;

    public DefaultContextBaselineRecorder(ContextBaselineProperties properties,
                                          ObjectMapper objectMapper,
                                          ObsInstrumentation instrumentation) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.hasher = new StableContextHasher(objectMapper);
        this.instrumentation = instrumentation;
    }

    @Override
    public ContextObservation begin(ContextCaptureRequest request) {
        if (!properties.isEnabled() || request == null) {
            return ContextObservation.disabled();
        }
        long started = System.nanoTime();
        try {
            List<ContextSectionMetric> metrics = new ArrayList<>();
            int chars = 0;
            int bytes = 0;
            int tokens = 0;
            Map<String, String> sectionHashes = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : request.sections().entrySet()) {
                String canonical = hasher.canonical(entry.getValue());
                String hash = properties.isHashEnabled() ? hasher.hashCanonical(canonical) : null;
                int sectionChars = canonical.length();
                int sectionBytes = canonical.getBytes(StandardCharsets.UTF_8).length;
                int sectionTokens = ContextSizeEstimator.estimateTokens(canonical);
                boolean containsRawUserText = containsRawText(entry.getKey());
                metrics.add(new ContextSectionMetric(entry.getKey(), sectionChars, sectionBytes,
                        sectionTokens, itemCount(entry.getValue()), hash,
                        topLevelKeys(entry.getValue()), containsRawUserText, containsRawUserText));
                chars += sectionChars;
                bytes += sectionBytes;
                tokens += sectionTokens;
                if (hash != null) {
                    sectionHashes.put(entry.getKey(), hash);
                }
            }
            ContextManifest manifest = new ContextManifest(
                    UUID.randomUUID().toString(), request.sessionId(), request.turnId(), request.operationId(),
                    request.agentName(), request.stage(), request.schemaVersion(),
                    request.requirementRevision(), request.constraintRevision(), request.planRevision(),
                    List.copyOf(metrics), chars, bytes, tokens,
                    properties.isHashEnabled() ? hasher.hash(sectionHashes) : null,
                    properties.getEstimateMethod(), (System.nanoTime() - started) / 1_000_000);
            return new ContextObservation(ContextObservation.ContextCaptureIdentity.from(request), manifest, true);
        } catch (RuntimeException e) {
            log.warn("[ContextBaseline] 上下文清单构造失败，忽略观测: {}", e.getMessage());
            return ContextObservation.disabled();
        }
    }

    @Override
    public void providerFinished(ContextObservation observation, TokenUsage usage,
                                 long durationMs, String status) {
        if (observation == null || !observation.enabled()) {
            return;
        }
        observation.setProviderDurationMs(Math.max(0L, durationMs));
        observation.setProviderStatus(status == null ? "UNKNOWN" : status);
        if (usage != null) {
            observation.setProviderInputTokens(usage.inputTokenCount());
            observation.setProviderOutputTokens(usage.outputTokenCount());
        }
    }

    @Override
    public void parseFinished(ContextObservation observation, String parseStatus,
                              String validationStatus, String errorCode) {
        if (observation == null || !observation.enabled() || !observation.markEmitted()) {
            return;
        }
        try {
            ContextManifest manifest = observation.manifest();
            ContextObservation.ContextCaptureIdentity identity = observation.identity();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("manifestId", manifest.manifestId());
            summary.put("turnId", manifest.turnId());
            summary.put("agentName", manifest.agentName());
            summary.put("stage", manifest.stage());
            summary.put("contextSchemaVersion", manifest.contextSchemaVersion());
            summary.put("requirementRevision", manifest.requirementRevision());
            summary.put("constraintRevision", manifest.constraintRevision());
            summary.put("planRevision", manifest.planRevision());
            summary.put("sectionNames", manifest.sections().stream().map(ContextSectionMetric::name).toList());
            summary.put("sections", manifest.sections());
            summary.put("totalChars", manifest.totalChars());
            summary.put("totalUtf8Bytes", manifest.totalUtf8Bytes());
            summary.put("estimatedInputTokens", manifest.estimatedInputTokens());
            summary.put("providerInputTokens", observation.providerInputTokens());
            summary.put("providerOutputTokens", observation.providerOutputTokens());
            summary.put("providerDurationMs", observation.providerDurationMs());
            summary.put("providerStatus", observation.providerStatus());
            summary.put("parseStatus", nullToUnknown(parseStatus));
            summary.put("validationStatus", nullToUnknown(validationStatus));
            summary.put("errorCode", errorCode);
            summary.put("payloadHash", manifest.payloadHash());
            summary.put("estimateMethod", manifest.estimateMethod());
            summary.put("buildDurationMs", manifest.buildDurationMs());
            String outcome = "SUCCESS".equals(observation.providerStatus())
                    && "SUCCESS".equals(parseStatus) ? "SUCCESS" : "FAILED";
            instrumentation.nodeEnded(identity.operationId(), identity.sessionId(), identity.ownerId(),
                    manifest.manifestId(), "context-manifest", outcome, summary);
            if (properties.isLogEnabled()) {
                log.info("[ContextBaseline] {}", objectMapper.writeValueAsString(summary));
            }
        } catch (Exception e) {
            log.warn("[ContextBaseline] 上下文观测写入失败，忽略: {}", e.getMessage());
        }
    }

    private int itemCount(Object value) {
        if (value == null) return 0;
        if (value instanceof Collection<?> c) return c.size();
        if (value instanceof Map<?, ?> m) return m.size();
        if (value.getClass().isArray()) return java.lang.reflect.Array.getLength(value);
        try {
            JsonNode node = objectMapper.valueToTree(value);
            return node.isArray() || node.isObject() ? node.size() : 1;
        } catch (Exception ignored) {
            return 1;
        }
    }

    private List<String> topLevelKeys(Object value) {
        try {
            JsonNode node = objectMapper.valueToTree(value);
            if (!node.isObject()) return List.of();
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(k -> {
                if (keys.size() < properties.getMaxKeyCount()) keys.add(k);
            });
            return List.copyOf(keys);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static boolean containsRawText(String name) {
        return ContextSectionNames.CURRENT_TURN.equals(name)
                || ContextSectionNames.UNRESOLVED_REQUIREMENTS.equals(name);
    }

    private static String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }
}
