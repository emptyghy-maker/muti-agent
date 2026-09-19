package com.ghy.mutiagent.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 大模型客户端配置（模型分层，兼顾速度与质量）。
 *
 * - defaultChatModel：常规任务，flash 快速模型，温度 0.2；
 * - sqlChatModel：高质量任务，更强的模型，温度 0.1；
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
            @Value("${llm.response-format:}") String responseFormat) {
        // 启动自检：一眼看出 base-url / 模型 / 密钥是否配置到位（密钥只打是否配置，不打内容）
        log.info("[LLM] base-url={} default 模型={} apiKey已配置={} responseFormat={}",
                baseUrl, model, apiKey != null && !apiKey.isBlank(), responseFormat);
        var b = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(0.2)
                .maxTokens(2048)
                .timeout(Duration.ofSeconds(180));
        // json_object 强制模型输出 JSON（思考模型常无视提示词输出 markdown，靠 API 参数兜底）
        if (responseFormat != null && !responseFormat.isBlank()) {
            b.responseFormat(responseFormat);
        }
        return b.build();
    }

    @Bean
    public ChatModel sqlChatModel(
            @Value("${llm.base-url}") String baseUrl,
            @Value("${llm.api-key}") String apiKey,
            @Value("${llm.models.sql}") String model,
            @Value("${llm.response-format:}") String responseFormat) {
        log.info("[LLM] sql 模型={} apiKey已配置={} responseFormat={}", model,
                apiKey != null && !apiKey.isBlank(), responseFormat);
        var b = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(0.1)
                .maxTokens(2048)
                // 行程规划是全流程最重的调用（3 天完整行程 JSON），服务端生成经常超过 180s；
                // 同时关闭自动重试：对超时类失败重试只会再烧一个完整窗口（180×3≈9 分钟才失败），
                // 失败后让用户手动重试更经济，且与 S08「不静默重试」语义一致
                .timeout(Duration.ofSeconds(300))
                .maxRetries(0);
        if (responseFormat != null && !responseFormat.isBlank()) {
            b.responseFormat(responseFormat);
        }
        return b.build();
    }

    /** 行程修复模型：快模型出修正稿（输出整份行程 JSON，token 上限放宽）；单次 120s 超时不重试 */
    @Bean
    public ChatModel repairChatModel(
            @Value("${llm.base-url}") String baseUrl,
            @Value("${llm.api-key}") String apiKey,
            @Value("${llm.models.default}") String model,
            @Value("${llm.response-format:}") String responseFormat) {
        log.info("[LLM] repair 模型={} apiKey已配置={} responseFormat={}", model,
                apiKey != null && !apiKey.isBlank(), responseFormat);
        var b = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(0.1)
                .maxTokens(4096)
                .timeout(Duration.ofSeconds(120))
                .maxRetries(0);
        if (responseFormat != null && !responseFormat.isBlank()) {
            b.responseFormat(responseFormat);
        }
        return b.build();
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
}
