package com.ghy.mutiagent.agent;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 行程修复 Agent（S08）：只针对确定性验证发现的违规做有限修复。
 *
 * 输入为结构化修复上下文（violations/targetNodeIds/requirements/当前计划 JSON，
 * 不含锁定的节点与任意 URL/工具指令），输出与规划同构的修正后完整行程 JSON。
 * 服务端对输出重新跑全局验证：提示词不是安全边界，模型说「已修复」不算数。
 * 独立于 ItineraryAgent：规划与修复是两个提供方角色，各自计量、各自计数。
 */
public interface ItineraryRepairAgent {

    @UserMessage("{{repairContext}}")
    Result<String> repair(@V("repairContext") String repairContext);
}
