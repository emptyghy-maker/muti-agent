package com.ghy.mutiagent.config;

import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmPlanningReasoningTest {

    @Test
    void ordinaryPlanningExplicitlyDisablesQwenDefaultXhighReasoning() {
        OpenAiChatRequestParameters parameters = LlmConfig.planningRequestParameters(" none ");

        assertEquals("none", parameters.reasoningEffort());
    }
}
