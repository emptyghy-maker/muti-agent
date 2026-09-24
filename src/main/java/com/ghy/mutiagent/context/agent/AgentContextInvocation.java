package com.ghy.mutiagent.context.agent;

import com.ghy.mutiagent.context.optimization.ContextReductionReport;

import java.util.LinkedHashMap;
import java.util.Map;

/** 一次角色上下文构造结果：实际参数、Hash、差异摘要与裁剪报告。 */
public record AgentContextInvocation(
        AgentContextRole role,
        AgentContextMode mode,
        String schemaVersion,
        AgentContextEnvelope<?> envelope,
        Map<String, String> arguments,
        String semanticHash,
        String promptHash,
        AgentContextDifference difference,
        ContextReductionReport reductionReport) {

    public AgentContextInvocation {
        arguments = arguments == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(arguments));
    }

    public String argument(String name) {
        return arguments.get(name);
    }

    public Map<String, Object> observationSections() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", role.name());
        out.put("mode", mode.name());
        out.put("schemaVersion", schemaVersion);
        out.put("semanticHash", semanticHash);
        out.put("promptHash", promptHash);
        if (difference != null) out.put("shadowDifference", difference);
        if (reductionReport != null) out.put("reduction", reductionReport);
        return out;
    }
}
