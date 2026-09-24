package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.ConstraintEntry;
import com.ghy.mutiagent.model.TravelState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequirementMergerTurnIdTest {

    @Test
    void usesRealTurnIdAsRequirementSource() {
        TravelState state = new TravelState();
        state.setSessionId("s1");
        RuleParseResult parsed = new RuleParseResult();
        ConstraintEntry entry = new ConstraintEntry();
        entry.setKey("interest");
        entry.setValue("PHOTO");
        parsed.getConstraints().add(entry);

        RequirementMerger.mergeInto(state, parsed, "turn-123");

        assertEquals("turn-123", state.getRequirementSnapshot().getConstraints().get(0).getSourceTurnId());
    }
}
