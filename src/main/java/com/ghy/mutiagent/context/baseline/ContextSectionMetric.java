package com.ghy.mutiagent.context.baseline;

import java.util.List;

/** 单个上下文功能段的规模和稳定指纹；不保存段落原文。 */
public record ContextSectionMetric(
        String name,
        int chars,
        int utf8Bytes,
        int estimatedTokens,
        int itemCount,
        String contentHash,
        List<String> topLevelKeys,
        boolean containsRawUserText,
        boolean sensitive) {
}
