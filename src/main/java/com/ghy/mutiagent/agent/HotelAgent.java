package com.ghy.mutiagent.agent;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 酒店推荐 Agent：从就近候选池中按「性价比/体验」偏好挑选酒店。
 * 系统提示词由 AgentConfig 统一组装（公共输出规范 + prompts/hotel.txt）。
 * 返回纯文本 JSON（Java 侧用 AgentOutputParser 统一解析，见 AttractionAgent 说明）。
 */
public interface HotelAgent {

    @UserMessage("候选池（已按距离排序）：{{pool}}\n\n用户偏好：{{preference}}\n\n输出格式：json")
    Result<String> select(@V("pool") String pool,
                          @V("preference") String preference);
}
