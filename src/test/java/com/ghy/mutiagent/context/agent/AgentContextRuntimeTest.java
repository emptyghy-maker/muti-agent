package com.ghy.mutiagent.context.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.context.optimization.CandidatePromptProjector;
import com.ghy.mutiagent.context.optimization.RequirementContextNormalizer;
import com.ghy.mutiagent.model.RequirementSnapshot;
import com.ghy.mutiagent.model.ResolvedPlanningPolicy;
import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.model.TravelState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentContextRuntimeTest {

    private ObjectMapper mapper;
    private AgentContextProperties properties;
    private AgentContextRuntime runtime;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        properties = new AgentContextProperties();
        AgentContextSerializer serializer = new AgentContextSerializer(mapper);
        runtime = new AgentContextRuntime(mapper, serializer,
                new AgentContextShadowComparator(serializer), properties,
                new RequirementContextNormalizer(), new CandidatePromptProjector());
    }

    @Test
    void shadowModeBuildsDtoButKeepsLegacyArgumentsByteForByte() {
        TravelState state = state(2);
        String pool = "[{\"id\":1,\"name\":\"甲\"},{\"id\":2,\"name\":\"乙\"}]";
        String preference = "{\"days\":1}";

        AgentContextInvocation result = runtime.candidate(state, AgentContextRole.ATTRACTION,
                pool, preference, 2);

        assertEquals(AgentContextMode.ENRICHED_SHADOW, result.mode());
        assertEquals(pool, result.argument("pool"));
        assertEquals(preference, result.argument("preference"));
        assertNotNull(result.envelope().payload());
        assertNotNull(result.semanticHash());
    }

    @Test
    void semanticHashIsStableAlthoughContextIdChanges() {
        TravelState state = state(1);
        AgentContextInvocation first = runtime.requirement(state, "turn-1", "{\"days\":1}");
        AgentContextInvocation second = runtime.requirement(state, "turn-1", "{\"days\":1}");

        assertNotEquals(first.envelope().contextId(), second.envelope().contextId());
        assertEquals(first.semanticHash(), second.semanticHash());
    }

    @Test
    void fullyDisabledConfigurationCanSkipAllNewContextWork() {
        properties.setShadowEnabled(false);
        properties.setEnabled(false);

        assertFalse(runtime.isActive(AgentContextRole.PLANNING));
        assertFalse(runtime.isActive(AgentContextRole.FOOD));
    }

    @Test
    void candidateRolloutProtectsSelectedItemWithoutChangingBusinessPool() throws Exception {
        properties.setEnabled(true);
        properties.setRolloutRoles("FOOD");
        properties.getCandidateK().setFood(2);
        TravelState state = state(1);
        state.setSelectedFoodIds(List.of(4L));
        String pool = "[{\"id\":1,\"name\":\"同名店\"},{\"id\":2,\"name\":\"同名店\"},"
                + "{\"id\":3,\"name\":\"丙\"},{\"id\":4,\"name\":\"锁定店\"}]";

        AgentContextInvocation result = runtime.candidate(state, AgentContextRole.FOOD,
                pool, "{}", 2);
        JsonNode projected = mapper.readTree(result.argument("pool"));

        assertEquals(AgentContextMode.ENRICHED_ROLLOUT, result.mode());
        assertEquals(2, projected.size());
        assertEquals(1, projected.get(0).get("id").asInt());
        assertEquals(4, projected.get(1).get("id").asInt());
        assertEquals(4, mapper.readTree(pool).size(), "业务候选池输入不得被修改");
        assertTrue(result.reductionReport().protectedCandidateKeys().contains("FOOD:4"));
    }

    @Test
    void planningRolloutAddsKnownFactsAndMarksMissingHoursUnknown() throws Exception {
        properties.setEnabled(true);
        properties.setRolloutRoles("PLANNING");
        TravelState state = state(3);
        TravelPreference preference = new TravelPreference();
        preference.setSpecialRequests("靠近新街口");
        state.setPreference(preference);
        state.setExtraRequest("靠近新街口");
        ResolvedPlanningPolicy policy = new ResolvedPlanningPolicy();
        policy.setRequirementSnapshotRevision(3);
        state.setResolvedPlanningPolicy(policy);
        String rules = "- 特殊需求只影响分天和顺序：「靠近新街口」。\n- 选点补充要求：「靠近新街口」。";

        AgentContextInvocation result = runtime.planning(state, "op-1", "{}", "[]",
                "[{\"id\":9,\"name\":\"餐厅\",\"cuisine\":\"本地菜\"}]", "[]", rules,
                List.of(Map.of("id", 9L, "avgPrice", new BigDecimal("80"), "source", "KB")));
        JsonNode food = mapper.readTree(result.argument("restaurants")).get(0);

        assertEquals(80, food.get("avgPrice").asInt());
        assertEquals("UNKNOWN", food.get("businessHoursStatus").asText());
        assertFalse(result.argument("rules").contains("选点补充要求"));
        assertTrue(result.reductionReport().reasonCodes().contains("RESTAURANT_FACTS_ADDED"));
    }

    @Test
    void planningRejectsMixedRequirementAndPolicyRevisions() {
        TravelState state = state(4);
        ResolvedPlanningPolicy policy = new ResolvedPlanningPolicy();
        policy.setRequirementSnapshotRevision(3);
        state.setResolvedPlanningPolicy(policy);

        AgentContextBuildException error = assertThrows(AgentContextBuildException.class,
                () -> runtime.planning(state, null, "{}", "[]", "[]", "[]", "规则", List.of()));
        assertEquals("REVISION_CONFLICT", error.code());
    }

    private static TravelState state(int requirementRevision) {
        TravelState state = new TravelState();
        state.setSessionId("s1");
        state.setPreference(new TravelPreference());
        RequirementSnapshot snapshot = new RequirementSnapshot();
        snapshot.setRevision(requirementRevision);
        state.setRequirementSnapshot(snapshot);
        return state;
    }
}
