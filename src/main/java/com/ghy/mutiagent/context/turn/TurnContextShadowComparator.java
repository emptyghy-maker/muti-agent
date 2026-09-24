package com.ghy.mutiagent.context.turn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 只比较新旧确定性结果，不修改会话，也不额外调用模型。 */
@Component
public class TurnContextShadowComparator {

    private static final Logger log = LoggerFactory.getLogger(TurnContextShadowComparator.class);

    public Comparison compare(TurnContext turn, Map<String, String> legacyUpdates) {
        Map<String, String> legacy = legacyUpdates == null ? Map.of() : legacyUpdates;
        Map<String, String> next = turn == null || turn.getInterpretation() == null
                ? Map.of() : turn.getInterpretation().getPreferenceUpdates();
        String outcome;
        if (Objects.equals(legacy, next)) {
            outcome = "MATCH";
        } else if (legacy.entrySet().containsAll(next.entrySet())) {
            outcome = "OLD_MORE_COMPLETE";
        } else if (next.entrySet().containsAll(legacy.entrySet())) {
            outcome = "NEW_MORE_COMPLETE";
        } else {
            outcome = "FIELD_CONFLICT";
        }
        Comparison comparison = new Comparison(outcome, new LinkedHashMap<>(legacy), new LinkedHashMap<>(next));
        log.info("[TurnContextShadow][sessionId={}][turnId={}] outcome={} legacyFields={} nextFields={}",
                turn == null ? null : turn.getSessionId(), turn == null ? null : turn.getTurnId(),
                outcome, legacy.keySet(), next.keySet());
        return comparison;
    }

    public record Comparison(String outcome, Map<String, String> legacy, Map<String, String> next) {
    }
}
