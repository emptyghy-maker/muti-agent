package com.ghy.mutiagent.context.optimization;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将确定性过滤器提供的“需求→合格地点键”转换为受保护集合。
 * 只相信服务端证据，不根据自然语言或模型理由猜测候选是否唯一合格。
 */
@Component
public class ProtectedCandidateResolver {

    public Resolution resolve(Set<String> lockedKeys, Set<String> selectedKeys,
                              Map<String, Set<String>> eligibleKeysByHardRequirement) {
        Set<String> keys = new LinkedHashSet<>();
        Map<String, List<String>> reasons = new LinkedHashMap<>();
        add(keys, reasons, lockedKeys, "USER_LOCKED");
        add(keys, reasons, selectedKeys, "USER_SELECTED");
        if (eligibleKeysByHardRequirement != null) {
            eligibleKeysByHardRequirement.forEach((requirementId, candidates) -> {
                if (candidates != null && candidates.size() == 1) {
                    String key = candidates.iterator().next();
                    keys.add(key);
                    reasons.put(key, List.of("UNIQUE_HARD_MATCH:" + requirementId));
                }
            });
        }
        return new Resolution(Set.copyOf(keys), Map.copyOf(reasons));
    }

    private static void add(Set<String> out, Map<String, List<String>> reasons,
                            Set<String> values, String reason) {
        if (values == null) return;
        for (String value : values) {
            if (value == null) continue;
            out.add(value);
            reasons.putIfAbsent(value, List.of(reason));
        }
    }

    public record Resolution(Set<String> protectedKeys, Map<String, List<String>> reasonCodes) { }
}
