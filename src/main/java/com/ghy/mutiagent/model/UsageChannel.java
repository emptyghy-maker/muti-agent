package com.ghy.mutiagent.model;

/**
 * 步骤执行渠道（用量审计的统一口径）：
 * KB=知识库/规则推荐，CACHE=缓存翻页，AGENT=AI 分析成功，RULE_FALLBACK=AI 失败走规则兜底，OP=纯操作。
 */
public final class UsageChannel {

    public static final String KB = "KB";
    public static final String CACHE = "CACHE";
    public static final String AGENT = "AGENT";
    public static final String RULE_FALLBACK = "RULE_FALLBACK";
    public static final String OP = "OP";

    /**
     * 渠道归并：优先级 AGENT &gt; RULE_FALLBACK &gt; KB &gt; CACHE &gt; OP。
     * 一轮对话里发生多种动作时，取"最有信息量"的那个渠道作为本轮口径。
     */
    public static String merge(String current, String incoming) {
        if (incoming == null || incoming.isBlank()) {
            return current;
        }
        if (current == null || current.isBlank()) {
            return incoming;
        }
        return rank(current) >= rank(incoming) ? current : incoming;
    }

    private static int rank(String c) {
        return switch (c) {
            case AGENT -> 4;
            case RULE_FALLBACK -> 3;
            case KB -> 2;
            case CACHE -> 1;
            default -> 0;
        };
    }

    private UsageChannel() {
    }
}
