package com.ghy.mutiagent.context.optimization;

import com.ghy.mutiagent.context.agent.AgentContextBuildException;
import com.ghy.mutiagent.context.agent.AgentContextSerializer;
import com.ghy.mutiagent.context.baseline.ContextSizeEstimator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 按段落语义做预算；只删除完整 OPTIONAL/AUDIT_ONLY 段，永不截断 JSON。 */
@Component
public class ContextBudgetPlanner {

    private final AgentContextSerializer serializer;

    public ContextBudgetPlanner(AgentContextSerializer serializer) {
        this.serializer = serializer;
    }

    public Result reduce(ContextBudgetPolicy policy, List<ContextSection> input) {
        List<ContextSection> source = input == null ? List.of() : List.copyOf(input);
        Map<String, ContextSection> retained = new LinkedHashMap<>();
        Map<String, Integer> dropped = new LinkedHashMap<>();
        for (ContextSection section : source) {
            if (section.requiredness() == ContextRequiredness.AUDIT_ONLY) {
                dropped.merge(section.name(), 1, Integer::sum);
            } else {
                retained.put(section.name(), section);
            }
        }
        int before = estimate(source);
        Set<String> order = new LinkedHashSet<>(policy.reductionOrder() == null
                ? List.of() : policy.reductionOrder());
        for (String name : order) {
            if (estimate(retained.values()) <= policy.targetEstimatedTokens()) break;
            ContextSection section = retained.get(name);
            if (section != null && section.requiredness() == ContextRequiredness.OPTIONAL) {
                retained.remove(name);
                dropped.merge(name, 1, Integer::sum);
            }
        }
        if (estimate(retained.values()) > policy.targetEstimatedTokens()) {
            for (ContextSection section : new ArrayList<>(retained.values())) {
                if (estimate(retained.values()) <= policy.targetEstimatedTokens()) break;
                if (section.requiredness() == ContextRequiredness.OPTIONAL) {
                    retained.remove(section.name());
                    dropped.merge(section.name(), 1, Integer::sum);
                }
            }
        }
        int after = estimate(retained.values());
        if (after > policy.hardMaxEstimatedTokens()) {
            throw new AgentContextBuildException("CONTEXT_REQUIRED_SECTION_OVER_BUDGET");
        }
        Map<String, Object> sections = new LinkedHashMap<>();
        retained.forEach((name, section) -> sections.put(name, section.value()));
        ContextReductionReport report = new ContextReductionReport(policy.role().name(), "budget-policy",
                before, after, 0, 0, List.of(), Map.copyOf(dropped), List.of(),
                dropped.isEmpty() ? List.of() : List.of("OPTIONAL_SECTION_DROPPED"), false);
        return new Result(Map.copyOf(sections), report);
    }

    private int estimate(Iterable<ContextSection> sections) {
        int total = 0;
        for (ContextSection section : sections) {
            total += ContextSizeEstimator.estimateTokens(serializer.serialize(section.value()));
        }
        return total;
    }

    public record Result(Map<String, Object> sections, ContextReductionReport report) { }
}
