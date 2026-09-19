package com.ghy.mutiagent.agent;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 行程补丁 Agent（S09）：根据用户调整诉求与当前行程，提出结构化局部补丁。
 * 只输出 JSON：{"baseRevision":1,"targetDays":[2],"operations":[{"op":"REMOVE"|"REPLACE_PLACE",
 * "nodeId":"d2-a2","placeKey":"ATTRACTION:99"}]}。
 * 服务端逐项验证白名单操作、nodeId 存在、范围（targetDays）、锁定约束与候选证据——
 * 提示词不是安全边界，模型说「已调整」不算数。
 */
public interface ItineraryPatchAgent {

    @UserMessage("{{patchContext}}")
    Result<String> propose(@V("patchContext") String patchContext);
}
