package com.ghy.mutiagent.context.optimization;

import java.util.List;

/** 去重后的有效需求；sourceFields 只用于审计，Prompt 中语义只渲染一次。 */
public record NormalizedRequirement(
        String requirementId,
        String subject,
        String operator,
        Object value,
        String unit,
        String scope,
        String hardness,
        int revision,
        List<String> sourceFields) {
}
