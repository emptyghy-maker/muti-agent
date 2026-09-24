package com.ghy.mutiagent.context.optimization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.context.agent.AgentContextRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CandidatePromptProjectorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CandidatePromptProjector projector = new CandidatePromptProjector();

    @Test
    void protectedCandidateBeyondNominalWindowIsRetainedInStableOrder() throws Exception {
        List<JsonNode> input = candidates(15);

        CandidatePromptProjector.Projection result = projector.project(AgentContextRole.ATTRACTION,
                input, 8, Set.of("ATTRACTION:15"), "v1", List.of("R-accessible"), 100);

        assertEquals(8, result.afterCount());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 15), result.candidates().stream()
                .map(n -> n.get("id").asInt()).toList());
        assertTrue(result.protectedKeys().contains("ATTRACTION:15"));
    }

    @Test
    void protectedItemsCanExceedNominalKAndSameNamesRemainDistinct() throws Exception {
        List<JsonNode> input = candidates(10);
        Set<String> protectedKeys = new LinkedHashSet<>();
        for (int i = 1; i <= 10; i++) protectedKeys.add("FOOD:" + i);

        CandidatePromptProjector.Projection result = projector.project(AgentContextRole.FOOD,
                input, 8, protectedKeys, "v1", List.of(), 100);

        assertEquals(10, result.afterCount());
        assertTrue(result.reasonCodes().contains("PROTECTED_CANDIDATES_EXCEED_LIMIT"));
        assertEquals(10, result.candidates().stream().map(n -> n.get("id").asLong()).distinct().count());
    }

    private List<JsonNode> candidates(int count) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            out.add(mapper.readTree("{\"id\":" + i + ",\"name\":\"同名\"}"));
        }
        return out;
    }
}
