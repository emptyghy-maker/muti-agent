package com.ghy.mutiagent.agent;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 美食推荐 Agent：从候选池中挑选店铺。
 * 系统提示词由 AgentConfig 统一组装（公共输出规范 + prompts/food.txt）。
 * 返回纯文本 JSON（Java 侧用 AgentOutputParser 统一解析，见 AttractionAgent 说明）。
 */
public interface FoodAgent {

    @UserMessage("候选池（每家店含 cuisine 风味字段）：{{pool}}\n\n用户偏好：{{preference}}\n\n目标数量：{{target}} 家左右\n\n输出格式：json")
    Result<String> select(@V("pool") String pool,
                          @V("preference") String preference,
                          @V("target") int target);
}
