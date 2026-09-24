package com.ghy.mutiagent.context.optimization;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghy.mutiagent.context.agent.AgentContextRole;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 在业务硬过滤之后生成模型候选视图；不修改原列表。 */
@Component
public class CandidatePromptProjector {

    public Projection project(AgentContextRole role, List<JsonNode> candidates, int nominalK,
                              Set<String> protectedKeys, String policyVersion,
                              List<String> retainedRequirementIds, int beforeTokens) {
        List<JsonNode> source = candidates == null ? List.of() : candidates;
        int safeK = nominalK <= 0 ? source.size() : nominalK;
        Map<String, JsonNode> protectedItems = new LinkedHashMap<>();
        for (JsonNode item : source) {
            String key = placeKey(role, item);
            if (key != null && protectedKeys.contains(key)) protectedItems.putIfAbsent(key, item);
        }

        int effectiveK = Math.max(safeK, protectedItems.size());
        Set<String> selected = new LinkedHashSet<>(protectedItems.keySet());
        for (JsonNode item : source) {
            if (selected.size() >= effectiveK) break;
            String key = placeKey(role, item);
            if (key != null) selected.add(key);
        }
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode item : source) {
            String key = placeKey(role, item);
            if (key != null && selected.contains(key)) out.add(item);
        }

        List<String> reasons = new ArrayList<>();
        if (out.size() < source.size()) reasons.add("LOW_PRIORITY_CANDIDATES_DROPPED");
        if (protectedItems.size() > safeK) reasons.add("PROTECTED_CANDIDATES_EXCEED_LIMIT");
        Set<String> missingProtected = new LinkedHashSet<>(protectedKeys);
        missingProtected.removeAll(protectedItems.keySet());
        if (!missingProtected.isEmpty()) reasons.add("PROTECTED_ITEM_NOT_IN_INPUT");
        return new Projection(List.copyOf(out), protectedItems.keySet().stream().toList(), reasons,
                source.size(), out.size(), beforeTokens, policyVersion, retainedRequirementIds);
    }

    public static String placeKey(AgentContextRole role, JsonNode item) {
        if (item == null || !item.hasNonNull("id")) return null;
        String prefix = switch (role) {
            case ATTRACTION -> "ATTRACTION";
            case FOOD -> "FOOD";
            case HOTEL -> "HOTEL";
            default -> role.name();
        };
        return prefix + ":" + item.get("id").asText();
    }

    public record Projection(
            List<JsonNode> candidates,
            List<String> protectedKeys,
            List<String> reasonCodes,
            int beforeCount,
            int afterCount,
            int beforeTokens,
            String policyVersion,
            List<String> retainedRequirementIds) {
    }
}
