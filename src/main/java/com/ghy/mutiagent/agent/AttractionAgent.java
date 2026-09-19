package com.ghy.mutiagent.agent;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 景点筛选 Agent：从候选池（Java 初筛结果）中按用户偏好挑选景点。
 * 只允许使用候选池中的 id（防幻觉，Java 侧还会做白名单校验）。
 * 系统提示词由 AgentConfig 统一组装（公共输出规范 prompts/common-format.txt + prompts/attraction.txt）。
 *
 * 返回纯文本 JSON（Java 侧用 AgentOutputParser 统一解析）——不用强类型返回，
 * 避免结构化输出（JSON Schema）在兼容端点上的兼容性问题（旧项目验证过的稳妥路线）。
 */
public interface AttractionAgent {

    @UserMessage("候选池：{{pool}}\n\n用户偏好：{{preference}}\n\n目标数量：{{target}} 个左右\n\n输出格式：json")
    Result<String> select(@V("pool") String pool,
                          @V("preference") String preference,
                          @V("target") int target);
}
