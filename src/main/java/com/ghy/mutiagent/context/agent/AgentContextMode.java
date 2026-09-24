package com.ghy.mutiagent.context.agent;

/** 新上下文相对现有 Agent 参数的接线模式。 */
public enum AgentContextMode {
    /** 只生成旧参数，完全关闭新上下文。 */
    LEGACY_COMPATIBLE,
    /** 构造并比较新上下文，正式模型调用仍使用旧参数。 */
    ENRICHED_SHADOW,
    /** 对配置中的角色发送优化后的参数。 */
    ENRICHED_ROLLOUT
}
