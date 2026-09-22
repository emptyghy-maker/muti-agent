package com.ghy.mutiagent.agent;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 需求解析 Agent：从用户回复中抽取旅行偏好字段（规则解析器命中不了时回退到这里）。
 *
 * 系统提示词由 AgentConfig 组装（prompts/preference.txt）。
 * 返回纯文本 JSON（Java 侧用 JsonUtils 宽容解析，见 AttractionAgent 说明）。
 * 注意：这里只做「信息抽取」，问题生成由 Java 侧按字段优先级 + 模板决定
 * （保证问题不重复、选项固定，不依赖 LLM 自觉）。
 */
public interface PreferenceAgent {

    @UserMessage("当前偏好：{{preference}}\n正在询问的字段：{{currentField}}\n当前问题：{{question}}\n用户回复：{{message}}\n\n输出格式：json")
    Result<String> parse(@V("preference") String preference,
                         @V("currentField") String currentField,
                         @V("question") String question,
                         @V("message") String message);
}
