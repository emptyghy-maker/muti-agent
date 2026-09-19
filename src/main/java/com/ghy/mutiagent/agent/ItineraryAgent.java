package com.ghy.mutiagent.agent;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 行程规划 Agent（核心）：按天组织行程，遵守饭点/劳累度硬约束。
 * 只输出结构化 JSON（type/placeId/time/note），名称与消费由 Java 侧回填与计算；
 * placeId 白名单校验 + 缺失餐点/休息点由 Java 强制补齐（硬约束不依赖 LLM 自觉）。
 * 系统提示词由 AgentConfig 组装（prompts/itinerary.txt，行程格式见 docs/agent-io-spec.md）。
 *
 * 返回纯文本 JSON（Java 侧用 JsonUtils 宽容解析，见 AttractionAgent 说明）。
 */
public interface ItineraryAgent {

    @UserMessage("用户偏好：{{preference}}\n\n可选景点（只可用其中 id）：{{attractions}}\n\n可选美食：{{restaurants}}\n\n酒店：{{hotel}}\n\n规则要求：{{rules}}\n\n输出格式：json")
    Result<String> plan(@V("preference") String preference,
                        @V("attractions") String attractions,
                        @V("restaurants") String restaurants,
                        @V("hotel") String hotel,
                        @V("rules") String rules);
}
