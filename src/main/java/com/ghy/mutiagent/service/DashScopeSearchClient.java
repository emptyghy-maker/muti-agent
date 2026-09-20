package com.ghy.mutiagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阿里云百炼联网检索客户端（阶段2）：直接调 OpenAI 兼容端点 + enable_search。
 * - 兼容模式（chat/completions）不返回引用来源链接，结果以模型整理后的结构化 JSON 为准；
 * - 联网搜索可能消耗付费额度，失败时由调用方降级（不阻塞主流程）。
 */
@Component
public class DashScopeSearchClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeSearchClient.class);

    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${llm.base-url}")
    private String baseUrl;
    @Value("${llm.api-key}")
    private String apiKey;
    /** 联网搜索模型（需支持 enable_search，如 qwen-plus/qwen-max/qwen3 系列） */
    @Value("${llm.search-model:qwen3.8-max}")
    private String searchModel;
    @Value("${llm.search-timeout-ms:90000}")
    private long timeoutMs;

    public DashScopeSearchClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 一次联网检索的原始结果（内容 + 用量） */
    public record SearchResult(String content, Integer promptTokens, Integer completionTokens) {
    }

    /** 发起一次带联网搜索的对话；非 2xx 抛 IOException（调用方降级） */
    public SearchResult search(String systemPrompt, String userPrompt) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", searchModel);
        body.put("temperature", 0.2);
        // 扩充检索：一次尽可能多返回（提示词上限 20 条），token 预算相应放宽
        body.put("max_tokens", 4096);
        body.put("enable_search", true);
        // O7 核实：max_tokens 不限制思考 token，开启思考时本调用实测稳定超时（90s 无产出）；
        // 结构化抽取任务不需要慢思考，显式关闭（原始 API 实测 -64% 耗时）
        body.put("enable_thinking", false);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        body.put("response_format", Map.of("type", "json_object"));

        String url = (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + "chat/completions";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("联网检索 HTTP " + resp.statusCode() + "：" + UsageService.clip(resp.body(), 300));
        }
        JsonNode root = objectMapper.readTree(resp.body());
        JsonNode msg = root.path("choices").path(0).path("message");
        String content = msg.path("content").asText("");
        if (content.isBlank()) {
            throw new IOException("联网检索返回内容为空");
        }
        JsonNode usage = root.path("usage");
        Integer pt = usage.path("prompt_tokens").isMissingNode() ? null : usage.path("prompt_tokens").asInt();
        Integer ct = usage.path("completion_tokens").isMissingNode() ? null : usage.path("completion_tokens").asInt();
        log.info("[DashScopeSearch] model={} promptTokens={} completionTokens={}", searchModel, pt, ct);
        return new SearchResult(content, pt, ct);
    }
}
