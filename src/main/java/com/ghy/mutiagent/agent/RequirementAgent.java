package com.ghy.mutiagent.agent;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 需求分析路由 Agent：偏好收集完成后，判断走标准流程还是 Agent 深度分析路径，
 * 并提炼贯穿全流程的关键关注点（如「情侣氛围」「夜景」「人均不高」）。
 * 系统提示词由 AgentConfig 组装（prompts/requirement.txt）。
 */
public interface RequirementAgent {

    @UserMessage("用户偏好：{{preference}}\n\n输出格式：json")
    Result<String> analyze(@V("preference") String preference);
}
