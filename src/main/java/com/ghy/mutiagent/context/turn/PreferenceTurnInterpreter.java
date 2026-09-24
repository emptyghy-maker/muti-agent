package com.ghy.mutiagent.context.turn;

import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.rule.RuleParseResult;
import com.ghy.mutiagent.rule.RulePreferenceParser;
import com.ghy.mutiagent.rule.SessionControlIntentParser;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 第二阶段的确定性解释器；不调用 LLM，不直接修改 TravelState。 */
@Component
public class PreferenceTurnInterpreter {

    private final ContextualScalarParser scalarParser;
    private final RulePreferenceParser rulePreferenceParser;

    public PreferenceTurnInterpreter(ContextualScalarParser scalarParser,
                                     RulePreferenceParser rulePreferenceParser) {
        this.scalarParser = scalarParser;
        this.rulePreferenceParser = rulePreferenceParser;
    }

    public TurnInterpretation interpret(TurnContext turn, TravelState state) {
        TurnInterpretation result = new TurnInterpretation();
        if (turn == null) {
            result.setParserSource("UNRESOLVED_FALLBACK");
            return result;
        }
        addControlIntent(turn);
        addDomainIntents(turn);
        RuleParseResult deterministic = rulePreferenceParser.parseResult(
                turn.getNormalizedMessage(), turn.getCurrentField(),
                state == null ? null : state.getPreference());
        result.getPreferenceUpdates().putAll(deterministic.getUpdates());
        result.getRequirementChanges().addAll(deterministic.getConstraints());
        if (deterministic.getUnresolvedText() != null && !deterministic.getUnresolvedText().isBlank()) {
            result.getUnresolvedTexts().add(deterministic.getUnresolvedText());
        }
        Map<String, String> scalar = scalarParser.parse(turn.getCurrentField(),
                turn.getNormalizedMessage(), state == null ? null : state.getPreference());
        // 当前问题短回答拥有最强上下文证据，覆盖同一字段的通用规则解释。
        result.getPreferenceUpdates().putAll(scalar);
        if (scalar.containsKey(turn.getCurrentField())) {
            result.setConsumedCurrentField(turn.getCurrentField());
            if (!turn.getIntents().contains(TurnIntent.ANSWER_CURRENT_FIELD)) {
                turn.getIntents().add(TurnIntent.ANSWER_CURRENT_FIELD);
            }
            result.setParserSource(deterministic.getUpdates().size() > 1
                    ? "CURRENT_FIELD_RULE+MULTI_FIELD_RULE" : "CURRENT_FIELD_RULE");
        } else {
            result.setParserSource(!deterministic.getUpdates().isEmpty() || !deterministic.getConstraints().isEmpty()
                    ? "MULTI_FIELD_RULE"
                    : turn.getIntents().isEmpty() ? "UNRESOLVED_FALLBACK" : "CONTROL_RULE");
        }
        result.getSkipChannels().addAll(turn.getIntents().stream().map(i -> switch (i) {
            case SKIP_ATTRACTION -> "ATTRACTION";
            case SKIP_FOOD -> "FOOD";
            case SKIP_HOTEL -> "HOTEL";
            default -> null;
        }).filter(java.util.Objects::nonNull).toList());
        turn.setInterpretation(result);
        return result;
    }

    private static void addControlIntent(TurnContext turn) {
        SessionControlIntentParser.Intent control = SessionControlIntentParser.parse(turn.getRawMessage());
        TurnIntent intent = switch (control.type()) {
            case RESTART -> TurnIntent.RESTART;
            case GO_BACK -> TurnIntent.STEP_BACK;
            case RESELECT_CURRENT, RESELECT_STAGE -> TurnIntent.REFINE_CANDIDATES;
            default -> null;
        };
        if (intent != null) turn.getIntents().add(intent);
    }

    private static void addDomainIntents(TurnContext turn) {
        String text = turn.getNormalizedMessage();
        if (text.matches(".*(?:不需要|不要|不用|不考虑|免了)\\s*(?:酒店|住宿|宾馆|旅馆).*$")) {
            turn.getIntents().add(TurnIntent.SKIP_HOTEL);
        }
        if (text.matches(".*(?:不需要|不要|不用|不考虑|免了)\\s*(?:美食|吃饭|餐厅|餐馆|饭店).*$")) {
            turn.getIntents().add(TurnIntent.SKIP_FOOD);
        }
        if (text.matches(".*(?:不需要|不要|不用|不考虑|免了|跳过|不去|不逛)\\s*(?:景点|景区|游玩|观光|游览).*$")) {
            turn.getIntents().add(TurnIntent.SKIP_ATTRACTION);
        }
        if (text.matches(".*(?:多给点|多给几个|再给点|再给几个|增加|扩充).*(?:景点|候选|餐厅|酒店)?.*")) {
            turn.getIntents().add(TurnIntent.EXPAND_CANDIDATES);
        }
        if (text.matches(".*(?:开始规划|开始推荐|直接开始|先开始|就这样|可以了)$")) {
            turn.getIntents().add(TurnIntent.COMPLETE_PREFERENCE);
        }
    }
}
