package com.ghy.mutiagent.context.optimization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.context.agent.AgentContextBuildException;
import com.ghy.mutiagent.context.agent.AgentContextRole;
import com.ghy.mutiagent.context.agent.AgentContextSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextBudgetPlannerTest {

    private final ContextBudgetPlanner planner = new ContextBudgetPlanner(
            new AgentContextSerializer(new ObjectMapper()));

    @Test
    void dropsWholeOptionalSectionAndKeepsValidStructure() {
        ContextBudgetPolicy policy = new ContextBudgetPolicy(AgentContextRole.PLANNING,
                5, 100, 0, Map.of(), List.of("displayText"), "TEST");
        ContextBudgetPlanner.Result result = planner.reduce(policy, List.of(
                new ContextSection("requirements", Map.of("days", 1), ContextRequiredness.REQUIRED),
                new ContextSection("displayText", "这是一段很长的可选展示文字".repeat(20),
                        ContextRequiredness.OPTIONAL)));

        assertTrue(result.sections().containsKey("requirements"));
        assertFalse(result.sections().containsKey("displayText"));
        assertEquals(1, result.report().droppedFieldCounts().get("displayText"));
    }

    @Test
    void requiredContentOverHardLimitFailsExplicitly() {
        ContextBudgetPolicy policy = new ContextBudgetPolicy(AgentContextRole.REPAIR,
                1, 2, 0, Map.of(), List.of(), "TEST");
        AgentContextBuildException error = assertThrows(AgentContextBuildException.class,
                () -> planner.reduce(policy, List.of(new ContextSection("violations",
                        "必须保留的违规".repeat(20), ContextRequiredness.REQUIRED))));
        assertEquals("CONTEXT_REQUIRED_SECTION_OVER_BUDGET", error.code());
    }
}
