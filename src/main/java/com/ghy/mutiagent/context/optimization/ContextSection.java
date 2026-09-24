package com.ghy.mutiagent.context.optimization;

/** 可独立预算的上下文段。value 保持结构化，禁止字符串截断 JSON。 */
public record ContextSection(String name, Object value, ContextRequiredness requiredness) {
}
