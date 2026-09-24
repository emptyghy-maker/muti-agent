package com.ghy.mutiagent.agent;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 行程规划 Agent（核心）：只负责已确认地点的分天、排序与正餐角色分配。
 * 只输出决策骨架（attraction: type/placeId；restaurant: type/placeId/note）；
 * 时间、交通、酒店、休息点、名称、费用与发布校验全部由 Java 确定性完成。
 * 系统提示词由 AgentConfig 组装（prompts/itinerary.txt，行程格式见 docs/agent-io-spec.md）。
 *
 * 返回纯文本 JSON（Java 侧用 JsonUtils 宽容解析，见 AttractionAgent 说明）。
 */
public interface ItineraryAgent {

    @UserMessage("规划偏好：{{preference}}\n\n已确认景点（必须全部安排，只可用其中 id）：{{attractions}}\n\n已确认餐厅（只可用其中 id）：{{restaurants}}\n\n住宿锚点（仅辅助判断片区，不要输出）：{{hotel}}\n\n规则要求：{{rules}}\n\n输出格式：json")
    Result<String> plan(@V("preference") String preference,
                        @V("attractions") String attractions,
                        @V("restaurants") String restaurants,
                        @V("hotel") String hotel,
                        @V("rules") String rules);
}
