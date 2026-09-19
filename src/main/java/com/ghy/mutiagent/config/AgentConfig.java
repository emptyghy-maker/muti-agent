package com.ghy.mutiagent.config;

import com.ghy.mutiagent.agent.AttractionAgent;
import com.ghy.mutiagent.agent.FoodAgent;
import com.ghy.mutiagent.agent.HotelAgent;
import com.ghy.mutiagent.agent.ItineraryAgent;
import com.ghy.mutiagent.agent.ItineraryPatchAgent;
import com.ghy.mutiagent.agent.ItineraryRepairAgent;
import com.ghy.mutiagent.agent.PreferenceAgent;
import com.ghy.mutiagent.agent.RequirementAgent;
import com.ghy.mutiagent.service.CancelRegistry;
import com.ghy.mutiagent.service.ItineraryRepairEngine;
import com.ghy.mutiagent.service.OperationBudgetConfig;
import com.ghy.mutiagent.service.TimeSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

import java.nio.charset.StandardCharsets;

/**
 * Agent 组装配置（旅游攻略规划）。
 *
 * 系统提示词统一在这里组装（不再用接口上的 @SystemMessage，避免多来源）：
 * - 候选类 Agent（景点/美食/酒店）：公共输出规范 prompts/common-format.txt + 各自专属提示词；
 * - 其余 Agent：各自专属提示词。
 * 输出格式的固定标准与解析规则见 docs/agent-io-spec.md（唯一规范文档）。
 *
 * 模型分层：
 * - 小任务（偏好解析/需求分析/候选重筛）：defaultChatModel（qwen3.7-flash，快且便宜）；
 * - 重任务（行程规划）：sqlChatModel（qwen3.8-max，多约束编排质量最关键）。
 */
@Configuration
public class AgentConfig {

    private final ResourceLoader resourceLoader;

    public AgentConfig(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /** 候选类系统提示词：公共输出规范 + Agent 专属提示词 */
    private String candidateSystemOf(String agentPrompt) {
        return read("classpath:prompts/common-format.txt") + "\n\n" + read("classpath:" + agentPrompt);
    }

    private String systemOf(String agentPrompt) {
        return read("classpath:" + agentPrompt);
    }

    private String read(String location) {
        try (var in = resourceLoader.getResource(location).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取提示词资源失败: " + location, e);
        }
    }

    /** 需求解析 Agent：小 JSON 抽取任务，用快模型（解析不准时规则解析器兜底） */
    @Bean
    public PreferenceAgent preferenceAgent(@Qualifier("defaultChatModel") ChatModel chatModel) {
        String sys = systemOf("prompts/preference.txt");
        return AiServices.builder(PreferenceAgent.class).chatModel(chatModel)
                .systemMessageProvider(id -> sys).build();
    }

    /** 景点筛选 Agent */
    @Bean
    public AttractionAgent attractionAgent(@Qualifier("defaultChatModel") ChatModel chatModel) {
        String sys = candidateSystemOf("prompts/attraction.txt");
        return AiServices.builder(AttractionAgent.class).chatModel(chatModel)
                .systemMessageProvider(id -> sys).build();
    }

    /** 美食推荐 Agent */
    @Bean
    public FoodAgent foodAgent(@Qualifier("defaultChatModel") ChatModel chatModel) {
        String sys = candidateSystemOf("prompts/food.txt");
        return AiServices.builder(FoodAgent.class).chatModel(chatModel)
                .systemMessageProvider(id -> sys).build();
    }

    /** 酒店推荐 Agent */
    @Bean
    public HotelAgent hotelAgent(@Qualifier("defaultChatModel") ChatModel chatModel) {
        String sys = candidateSystemOf("prompts/hotel.txt");
        return AiServices.builder(HotelAgent.class).chatModel(chatModel)
                .systemMessageProvider(id -> sys).build();
    }

    /** 需求分析路由 Agent：workflow / agent 路径判定 + 关键关注点提炼（小 JSON 任务，用快模型） */
    @Bean
    public RequirementAgent requirementAgent(@Qualifier("defaultChatModel") ChatModel chatModel) {
        String sys = systemOf("prompts/requirement.txt");
        return AiServices.builder(RequirementAgent.class).chatModel(chatModel)
                .systemMessageProvider(id -> sys).build();
    }

    /** 行程规划 Agent：多约束编排（质量最关键），用更强模型 */
    @Bean
    public ItineraryAgent itineraryAgent(@Qualifier("sqlChatModel") ChatModel chatModel) {
        String sys = systemOf("prompts/itinerary.txt");
        return AiServices.builder(ItineraryAgent.class).chatModel(chatModel)
                .systemMessageProvider(id -> sys).build();
    }

    /** 行程修复 Agent（S08）快通道：定向修正任务用快模型，秒级出稿；独立接口 = 独立提供方角色、独立计量 */
    @Bean
    public ItineraryRepairAgent itineraryRepairAgent(@Qualifier("repairChatModel") ChatModel chatModel) {
        String sys = systemOf("prompts/repair.txt");
        return AiServices.builder(ItineraryRepairAgent.class).chatModel(chatModel)
                .systemMessageProvider(id -> sys).build();
    }

    /** 行程修复 Agent（S08）强兜底：快模型修复后校验仍不过时，第二轮用强模型再修一次 */
    @Bean
    public ItineraryRepairAgent itineraryRepairAgentHeavy(@Qualifier("sqlChatModel") ChatModel chatModel) {
        String sys = systemOf("prompts/repair.txt");
        return AiServices.builder(ItineraryRepairAgent.class).chatModel(chatModel)
                .systemMessageProvider(id -> sys).build();
    }

    /** 行程补丁 Agent（S09）：结构化局部补丁小 JSON 任务，用快模型 */
    @Bean
    public ItineraryPatchAgent itineraryPatchAgent(@Qualifier("defaultChatModel") ChatModel chatModel) {
        String sys = systemOf("prompts/patch.txt");
        return AiServices.builder(ItineraryPatchAgent.class).chatModel(chatModel)
                .systemMessageProvider(id -> sys).build();
    }

    /** S08 修复引擎：共享预算（调用配额/token/截止/分块粒度）生产默认口径，装配进 ItineraryService */
    @Bean
    public ItineraryRepairEngine itineraryRepairEngine(
                                                       @Qualifier("itineraryRepairAgent") ItineraryRepairAgent repairAgent,
                                                       @Qualifier("itineraryRepairAgentHeavy") ItineraryRepairAgent repairAgentHeavy,
                                                       ObjectMapper objectMapper,
                                                       @Value("${travel.repair.max-repairs:2}") int maxRepairs,
                                                       @Value("${travel.repair.max-model-calls:3}") int maxModelCalls,
                                                       @Value("${travel.repair.token-budget:200000}") long tokenBudget,
                                                       @Value("${travel.repair.max-output-tokens:4096}") long maxOutputTokens,
                                                       // deadline 须覆盖 maxModelCalls×单次模型超时（3×300s），否则慢而成功的
                                                       // 规划会在「提交前复查」被误杀（表现为生成成功仍报「操作超出截止时间」）
                                                       @Value("${travel.repair.deadline-ms:900000}") long deadlineMs,
                                                       @Value("${travel.repair.chunk-days:3}") int chunkDays) {
        return new ItineraryRepairEngine(repairAgent, repairAgentHeavy, objectMapper, TimeSource.SYSTEM,
                new OperationBudgetConfig(maxRepairs, maxModelCalls, tokenBudget, maxOutputTokens,
                        deadlineMs, chunkDays));
    }

    /** S11 取消注册表：持久化取消在操作行，进程内信号通知在途执行线程（显式取消与 SSE 断开同路径） */
    @Bean
    public CancelRegistry cancelRegistry() {
        return new CancelRegistry();
    }
}
