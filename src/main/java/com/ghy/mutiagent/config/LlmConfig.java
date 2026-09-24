package com.ghy.mutiagent.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 大模型客户端配置（模型分层，兼顾速度与质量）。
 *
 * - defaultChatModel：常规任务，flash 快速模型池，温度 0.2；
 * - sqlChatModel：高质量任务，规划模型池，温度 0.1；
 * - streamingChatModel：SSE 流式输出，同样用 flash 快速模型。
 *
 * maxTokens 限制最大输出长度，避免模型长篇大论拖慢生成速度。
 * timeout 只是上限兜底，不是耗时来源；sqlChatModel（行程规划）单独放宽到 300s 且不自动重试
 * （见该 bean 注释），避免「180s×2 重试≈9 分钟」的最坏等待。
 */
@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public ChatModel defaultChatModel(
            @Value("${llm.base-url}") String baseUrl,
            @Value("${llm.api-key}") String apiKey,
            @Value("${llm.models.default}") String model,
            @Value("${llm.models.default-fallbacks:}") String fallbacks,
            @Value("${llm.routing.mode:FAILOVER}") String routingMode,
            @Value("${llm.routing.cooldown-ms:1800000}") long cooldownMs,
            @Value("${llm.routing.failover-on-timeout:false}") boolean failoverOnTimeout,
            @Value("${llm.response-format:}") String responseFormat) {
        // 启动自检：一眼看出 base-url / 模型 / 密钥是否配置到位（密钥只打是否配置，不打内容）
        log.info("[LLM] base-url={} default 模型={} apiKey已配置={} responseFormat={}",
                baseUrl, model, apiKey != null && !apiKey.isBlank(), responseFormat);
        return routed("fast", baseUrl, apiKey, model, fallbacks, responseFormat,
                0.2, 2048, 180, routingMode, cooldownMs, failoverOnTimeout);
    }

    @Bean
    public ChatModel sqlChatModel(
            @Value("${llm.base-url}") String baseUrl,
            @Value("${llm.api-key}") String apiKey,
            @Value("${llm.models.sql}") String model,
            @Value("${llm.models.sql-fallbacks:}") String fallbacks,
            @Value("${llm.routing.mode:FAILOVER}") String routingMode,
            @Value("${llm.routing.cooldown-ms:1800000}") long cooldownMs,
            @Value("${llm.routing.failover-on-timeout:false}") boolean failoverOnTimeout,
            @Value("${llm.models.sql-reasoning-effort:none}") String reasoningEffort,
            @Value("${llm.models.sql-max-tokens:1024}") int maxTokens,
            @Value("${llm.response-format:}") String responseFormat) {
        log.info("[LLM] sql 模型={} apiKey已配置={} responseFormat={} reasoningEffort={} maxTokens={}", model,
                apiKey != null && !apiKey.isBlank(), responseFormat, reasoningEffort, maxTokens);
        return routed("plan", baseUrl, apiKey, model, fallbacks, responseFormat,
                0.1, maxTokens, 300, routingMode, cooldownMs, failoverOnTimeout, reasoningEffort);
    }

    /** 行程修复模型：快模型出修正稿（输出整份行程 JSON，token 上限放宽）；单次 120s 超时不重试 */
    @Bean
    public ChatModel repairChatModel(
            @Value("${llm.base-url}") String baseUrl,
            @Value("${llm.api-key}") String apiKey,
            @Value("${llm.models.repair:${llm.models.default}}") String model,
            @Value("${llm.models.repair-fallbacks:}") String fallbacks,
            @Value("${llm.routing.mode:FAILOVER}") String routingMode,
            @Value("${llm.routing.cooldown-ms:1800000}") long cooldownMs,
            @Value("${llm.routing.failover-on-timeout:false}") boolean failoverOnTimeout,
            @Value("${llm.response-format:}") String responseFormat) {
        log.info("[LLM] repair 模型={} apiKey已配置={} responseFormat={}", model,
                apiKey != null && !apiKey.isBlank(), responseFormat);
        return routed("repair", baseUrl, apiKey, model, fallbacks, responseFormat,
                0.1, 4096, 120, routingMode, cooldownMs, failoverOnTimeout, null);
    }

    /** 流式模型：SSE 边生成边推送报告 */
    @Bean
    public StreamingChatModel streamingChatModel(
            @Value("${llm.base-url}") String baseUrl,
            @Value("${llm.api-key}") String apiKey,
            @Value("${llm.models.default}") String model,
            @Value("${llm.response-format:}") String responseFormat) {
        var b = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(0.2)
                .maxTokens(1024)
                .timeout(Duration.ofSeconds(180));
        if (responseFormat != null && !responseFormat.isBlank()) {
            b.responseFormat(responseFormat);
        }
        return b.build();
    }

    private ChatModel routed(String route, String baseUrl, String apiKey,
                             String primary, String fallbackCsv, String responseFormat,
                             double temperature, int maxTokens, int timeoutSeconds,
                             String routingMode, long cooldownMs, boolean failoverOnTimeout) {
        return routed(route, baseUrl, apiKey, primary, fallbackCsv, responseFormat,
                temperature, maxTokens, timeoutSeconds, routingMode, cooldownMs, failoverOnTimeout, null);
    }

    private ChatModel routed(String route, String baseUrl, String apiKey,
                             String primary, String fallbackCsv, String responseFormat,
                             double temperature, int maxTokens, int timeoutSeconds,
                             String routingMode, long cooldownMs, boolean failoverOnTimeout,
                             String reasoningEffort) {
        List<String> names = modelNames(primary, fallbackCsv);
        List<FailoverChatModel.Candidate> candidates = new ArrayList<>();
        for (String name : names) {
            var builder = OpenAiChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .modelName(name)
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    // 每个物理模型只请求一次；跨模型故障转移由 FailoverChatModel 明确记录。
                    .maxRetries(0);
            if (reasoningEffort != null && !reasoningEffort.isBlank()) {
                builder.defaultRequestParameters(planningRequestParameters(reasoningEffort));
            }
            if (responseFormat != null && !responseFormat.isBlank()) {
                builder.responseFormat(responseFormat);
            }
            candidates.add(new FailoverChatModel.Candidate(name, builder.build()));
        }
        return new FailoverChatModel(route, candidates, routingMode, cooldownMs, failoverOnTimeout);
    }

    /** 百炼 Qwen3.8 默认使用 xhigh 思考；普通行程只需结构化排序，显式 none 避免隐藏推理长尾。 */
    static OpenAiChatRequestParameters planningRequestParameters(String reasoningEffort) {
        return OpenAiChatRequestParameters.builder()
                .reasoningEffort(reasoningEffort == null || reasoningEffort.isBlank()
                        ? "none" : reasoningEffort.trim())
                .build();
    }

    private static List<String> modelNames(String primary, String fallbackCsv) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (primary != null && !primary.isBlank()) {
            names.add(primary.trim());
        }
        if (fallbackCsv != null) {
            for (String value : fallbackCsv.split(",")) {
                if (!value.isBlank()) {
                    names.add(value.trim());
                }
            }
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException("模型路由至少需要一个模型");
        }
        return List.copyOf(names);
    }
}
