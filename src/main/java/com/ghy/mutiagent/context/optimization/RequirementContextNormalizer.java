package com.ghy.mutiagent.context.optimization;

import com.ghy.mutiagent.model.ConstraintEntry;
import com.ghy.mutiagent.model.RequirementSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 只按结构化身份去重，不使用 LLM，也不把 SOFT/PROPOSED 提升为 HARD。 */
@Component
public class RequirementContextNormalizer {

    public List<NormalizedRequirement> normalize(RequirementSnapshot snapshot) {
        if (snapshot == null || snapshot.getConstraints() == null) return List.of();
        Map<String, NormalizedRequirement> byIdentity = new LinkedHashMap<>();
        for (ConstraintEntry entry : snapshot.getConstraints()) {
            if (entry == null || (entry.getStatus() != null
                    && !"ACTIVE".equalsIgnoreCase(entry.getStatus()))) continue;
            String identity = identity(entry);
            NormalizedRequirement next = toNormalized(entry);
            NormalizedRequirement current = byIdentity.get(identity);
            if (current == null || next.revision() > current.revision()) {
                byIdentity.put(identity, next);
            } else if (next.revision() == current.revision()) {
                List<String> sources = new ArrayList<>(current.sourceFields());
                for (String source : next.sourceFields()) if (!sources.contains(source)) sources.add(source);
                byIdentity.put(identity, new NormalizedRequirement(current.requirementId(), current.subject(),
                        current.operator(), current.value(), current.unit(), current.scope(), current.hardness(),
                        current.revision(), List.copyOf(sources)));
            }
        }
        return List.copyOf(byIdentity.values());
    }

    public List<String> unresolved(RequirementSnapshot snapshot) {
        if (snapshot == null || snapshot.getUnparsedTexts() == null) return List.of();
        return snapshot.getUnparsedTexts().stream()
                .filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().toList();
    }

    private static String identity(ConstraintEntry e) {
        String subject = e.getSubject() == null
                || "UNKNOWN".equals(e.getSubject().name()) ? nullToEmpty(e.getKey()) : e.getSubject().name();
        String scope = e.getScope() == null ? "NONE" : e.getScope().name();
        String hardness = normalizedHardness(e.getHardness());
        String operator = e.getOperator() == null ? "" : e.getOperator().name();
        String value = e.getCount() == null ? nullToEmpty(e.getValue()) : String.valueOf(e.getCount());
        String unit = e.getUnit() == null ? "" : e.getUnit().name();
        return subject.toUpperCase(Locale.ROOT) + "|" + operator + "|" + value.trim()
                + "|" + unit + "|" + scope + "|" + hardness;
    }

    private static NormalizedRequirement toNormalized(ConstraintEntry e) {
        Object value = e.getCount() == null ? e.getValue() : e.getCount();
        String source = e.getSource() == null || e.getSource().isBlank() ? "requirements" : e.getSource();
        return new NormalizedRequirement(e.getId(),
                e.getSubject() == null || "UNKNOWN".equals(e.getSubject().name())
                        ? e.getKey() : e.getSubject().name(),
                e.getOperator() == null ? null : e.getOperator().name(), value,
                e.getUnit() == null ? null : e.getUnit().name(),
                e.getScope() == null ? null : e.getScope().name(),
                normalizedHardness(e.getHardness()), e.getRevision(), List.of(source));
    }

    private static String normalizedHardness(String hardness) {
        if (hardness == null || hardness.isBlank()) return "SOFT";
        String value = hardness.toUpperCase(Locale.ROOT);
        return switch (value) {
            case "HARD", "SOFT", "PROPOSED" -> value;
            default -> "SOFT";
        };
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }
}
