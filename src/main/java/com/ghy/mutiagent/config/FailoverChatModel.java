package com.ghy.mutiagent.config;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生产模型故障转移：只对额度、限流、模型不可用和可选的超时错误换模型。
 * 可解析但质量不合格的响应不会在这里换模型，仍交给业务校验/修复流程处理。
 */
public final class FailoverChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(FailoverChatModel.class);

    public enum Mode {
        FAILOVER,
        FIXED;

        static Mode parse(String value) {
            return "FIXED".equalsIgnoreCase(value) ? FIXED : FAILOVER;
        }
    }

    public record Candidate(String name, ChatModel model) {
        public Candidate {
            if (name == null || name.isBlank() || model == null) {
                throw new IllegalArgumentException("模型候选的 name/model 不能为空");
            }
        }
    }

    private final String route;
    private final List<Candidate> candidates;
    private final Mode mode;
    private final long cooldownMs;
    private final boolean failoverOnTimeout;
    private final Clock clock;
    private final Map<String, Long> blockedUntil = new ConcurrentHashMap<>();

    public FailoverChatModel(String route, List<Candidate> candidates, String mode,
                             long cooldownMs, boolean failoverOnTimeout) {
        this(route, candidates, Mode.parse(mode), cooldownMs, failoverOnTimeout, Clock.systemUTC());
    }

    FailoverChatModel(String route, List<Candidate> candidates, Mode mode,
                      long cooldownMs, boolean failoverOnTimeout, Clock clock) {
        this.route = route;
        this.candidates = deduplicate(candidates);
        this.mode = mode;
        this.cooldownMs = Math.max(1_000L, cooldownMs);
        this.failoverOnTimeout = failoverOnTimeout;
        this.clock = clock;
        if (this.candidates.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个模型候选");
        }
        log.info("[LLM_ROUTE] route={} mode={} models={}", route, mode,
                this.candidates.stream().map(Candidate::name).toList());
    }

    private static List<Candidate> deduplicate(List<Candidate> source) {
        Map<String, Candidate> unique = new LinkedHashMap<>();
        if (source != null) {
            for (Candidate candidate : source) {
                if (candidate != null) {
                    unique.putIfAbsent(candidate.name(), candidate);
                }
            }
        }
        return List.copyOf(unique.values());
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        List<Candidate> usable = usableCandidates();
        RuntimeException last = null;
        for (int i = 0; i < usable.size(); i++) {
            Candidate candidate = usable.get(i);
            LlmRouteContext.record(route, candidate.name());
            try {
                ChatResponse response = candidate.model().chat(request);
                blockedUntil.remove(candidate.name());
                if (i > 0) {
                    log.info("[LLM_ROUTE] route={} fallbackSucceeded model={} attempt={}",
                            route, candidate.name(), i + 1);
                }
                return response;
            } catch (RuntimeException error) {
                last = error;
                boolean eligible = isFailoverEligible(error, failoverOnTimeout);
                boolean hasNext = i + 1 < usable.size();
                if (!eligible || !hasNext || mode == Mode.FIXED) {
                    throw error;
                }
                blockedUntil.put(candidate.name(), clock.millis() + cooldownMs);
                log.warn("[LLM_ROUTE] route={} model={} failed; switching to {} reason={}",
                        route, candidate.name(), usable.get(i + 1).name(), concise(error));
            }
        }
        throw last == null ? new IllegalStateException("没有可用模型") : last;
    }

    private List<Candidate> usableCandidates() {
        if (mode == Mode.FIXED) {
            return List.of(candidates.get(0));
        }
        long now = clock.millis();
        List<Candidate> usable = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (blockedUntil.getOrDefault(candidate.name(), 0L) <= now) {
                usable.add(candidate);
            }
        }
        // 所有模型都在冷却时允许主模型探测一次，避免路由永久失活。
        return usable.isEmpty() ? List.of(candidates.get(0)) : usable;
    }

    public static boolean isFailoverEligible(Throwable error, boolean includeTimeout) {
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            if (includeTimeout && (cursor instanceof HttpTimeoutException
                    || cursor instanceof SocketTimeoutException
                    || cursor.getClass().getSimpleName().contains("Timeout"))) {
                return true;
            }
            String message = String.valueOf(cursor.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("allocationquota")
                    || message.contains("freetieronly")
                    || message.contains("free quota exhausted")
                    || message.contains("quota exhausted")
                    || message.contains("rate limit")
                    || message.contains("too many requests")
                    || message.contains("http 429")
                    || message.contains("status code: 429")
                    || message.contains("service unavailable")
                    || message.contains("temporarily unavailable")
                    || message.contains("http 503")
                    || message.contains("status code: 503")
                    || message.contains("model not found")
                    || message.contains("model_not_found")
                    || message.contains("model is not activated")) {
                return true;
            }
        }
        return false;
    }

    private static String concise(Throwable error) {
        String value = error.getClass().getSimpleName() + ": " + error.getMessage();
        return value.length() <= 300 ? value : value.substring(0, 300);
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        // 不返回主模型名，否则它会覆盖备用 delegate 自己的 modelName。
        return ChatRequestParameters.builder().build();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return candidates.get(0).model().supportedCapabilities();
    }

    @Override
    public ModelProvider provider() {
        return ModelProvider.OTHER;
    }
}
