package com.ghy.mutiagent.context.agent;

import com.ghy.mutiagent.context.baseline.ContextSizeEstimator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AgentContextShadowComparator {

    private final AgentContextSerializer serializer;

    public AgentContextShadowComparator(AgentContextSerializer serializer) {
        this.serializer = serializer;
    }

    public AgentContextDifference compare(String legacy, String enriched) {
        String oldText = legacy == null ? "" : legacy;
        String newText = enriched == null ? "" : enriched;
        String oldHash = serializer.hashSerialized(oldText);
        String newHash = serializer.hashSerialized(newText);
        List<String> reasons = new ArrayList<>();
        if (!oldHash.equals(newHash)) reasons.add("PAYLOAD_CHANGED");
        if (ContextSizeEstimator.estimateTokens(newText) < ContextSizeEstimator.estimateTokens(oldText)) {
            reasons.add("ESTIMATED_TOKEN_REDUCED");
        }
        return new AgentContextDifference(oldHash.equals(newHash),
                ContextSizeEstimator.estimateTokens(oldText),
                ContextSizeEstimator.estimateTokens(newText), oldHash, newHash, List.copyOf(reasons));
    }
}
