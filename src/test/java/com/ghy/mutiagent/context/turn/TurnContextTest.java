package com.ghy.mutiagent.context.turn;

import com.ghy.mutiagent.model.Question;
import com.ghy.mutiagent.model.RequirementSnapshot;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.model.enums.TravelStage;
import com.ghy.mutiagent.rule.RulePreferenceParser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TurnContextTest {

    @Test
    void factoryCapturesQuestionStageAndVersionsWithoutChangingState() {
        TravelState state = new TravelState();
        state.setSessionId("s1");
        state.setStage(TravelStage.PREFERENCE);
        state.setCurrentField("totalBudget");
        state.setConstraintRevision(4);
        state.setPlanRevision(2);
        RequirementSnapshot snapshot = new RequirementSnapshot();
        snapshot.setRevision(3);
        state.setRequirementSnapshot(snapshot);
        Question question = new Question();
        question.setField("totalBudget");
        question.setText("预算大概多少？");

        TurnContext turn = new TurnContextFactory().create(state, " ６００以内 ", null,
                Map.of("totalBudget", question));

        assertEquals(" ６００以内 ", turn.getRawMessage());
        assertEquals("600以内", turn.getNormalizedMessage());
        assertEquals("totalBudget", turn.getCurrentField());
        assertEquals("预算大概多少？", turn.getCurrentQuestion());
        assertEquals(3, turn.getRequirementRevisionBefore());
        assertEquals(4, turn.getConstraintRevisionBefore());
        assertEquals(2, turn.getPlanRevisionBefore());
        assertNull(state.getPreference().getTotalBudget());
    }

    @Test
    void contextualScalarUsesCurrentQuestionAndRejectsWrongUnit() {
        ContextualScalarParser parser = new ContextualScalarParser();
        assertEquals("600", parser.parse("totalBudget", "600以内", null).get("totalBudget"));
        assertTrue(parser.parse("totalBudget", "两个人", null).isEmpty());
        assertEquals("2", parser.parse("peopleCount", "2", null).get("peopleCount"));
        assertEquals("1", parser.parse("days", "1", null).get("days"));
    }

    @Test
    void perPersonBudgetUsesPeopleFromSameTurnOrConfirmedPreference() {
        ContextualScalarParser parser = new ContextualScalarParser();
        assertEquals("600", parser.parse("totalBudget", "人均300，两个人", null).get("totalBudget"));

        TravelState state = new TravelState();
        state.getPreference().setPeopleCount(3);
        assertEquals("900", parser.parse("totalBudget", "人均300以内", state.getPreference())
                .get("totalBudget"));
    }

    @Test
    void interpreterSeparatesControlAndSkipIntentFromPreferenceUpdates() {
        TravelState state = new TravelState();
        state.setSessionId("s1");
        state.setStage(TravelStage.PREFERENCE);
        state.setCurrentField("hotelStyle");
        TurnContext turn = new TurnContextFactory().create(state, "不要酒店", null, Map.of());
        PreferenceTurnInterpreter interpreter = new PreferenceTurnInterpreter(
                new ContextualScalarParser(), new RulePreferenceParser());

        TurnInterpretation interpretation = interpreter.interpret(turn, state);

        assertTrue(turn.getIntents().contains(TurnIntent.SKIP_HOTEL));
        assertTrue(interpretation.getSkipChannels().contains("HOTEL"));
        assertFalse(interpretation.getPreferenceUpdates().containsKey("specialRequests"));
    }

    @Test
    void interpreterKeepsMultipleFieldsAndCompletionIntent() {
        TravelState state = new TravelState();
        state.setStage(TravelStage.PREFERENCE);
        state.setCurrentField("days");
        TurnContext turn = new TurnContextFactory().create(state,
                "两个人玩一天，预算600，然后开始推荐", null, Map.of());
        PreferenceTurnInterpreter interpreter = new PreferenceTurnInterpreter(
                new ContextualScalarParser(), new RulePreferenceParser());

        TurnInterpretation result = interpreter.interpret(turn, state);

        assertEquals("1", result.getPreferenceUpdates().get("days"));
        assertEquals("2", result.getPreferenceUpdates().get("peopleCount"));
        assertEquals("600", result.getPreferenceUpdates().get("totalBudget"));
        assertTrue(turn.getIntents().contains(TurnIntent.COMPLETE_PREFERENCE));
    }

    @Test
    void changePlannerDescribesBeforeAfterWithoutApplyingIt() {
        TravelState state = new TravelState();
        state.getPreference().setTotalBudget(new BigDecimal("1000"));
        TurnInterpretation interpretation = new TurnInterpretation();
        interpretation.getPreferenceUpdates().put("totalBudget", "600");
        TurnContext turn = new TurnContext();

        ContextChangeSet changes = new TurnChangePlanner().plan(turn, state, interpretation);

        assertEquals(new BigDecimal("1000"), changes.getOldValues().get("totalBudget"));
        assertEquals("600", changes.getNewValues().get("totalBudget"));
        assertEquals(new BigDecimal("1000"), state.getPreference().getTotalBudget());
        assertTrue(changes.isInvalidatePlan());
        assertEquals(List.of("ATTRACTION", "FOOD", "HOTEL"),
                List.copyOf(changes.getAffectedChannels()));
    }

    @Test
    void shadowComparatorOnlyReportsDifferenceAndDoesNotChangeState() {
        TravelState state = new TravelState();
        state.getPreference().setDays(3);
        TurnContext turn = new TurnContext();
        turn.setSessionId("s-shadow");
        turn.setTurnId("turn-shadow");
        TurnInterpretation interpretation = new TurnInterpretation();
        interpretation.getPreferenceUpdates().put("days", "1");
        interpretation.getPreferenceUpdates().put("peopleCount", "2");
        turn.setInterpretation(interpretation);

        TurnContextShadowComparator.Comparison comparison = new TurnContextShadowComparator()
                .compare(turn, Map.of("days", "1"));

        assertEquals("NEW_MORE_COMPLETE", comparison.outcome());
        assertEquals(3, state.getPreference().getDays());
        assertNull(state.getPreference().getPeopleCount());
    }
}
