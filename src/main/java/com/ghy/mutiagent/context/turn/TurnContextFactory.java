package com.ghy.mutiagent.context.turn;

import com.ghy.mutiagent.model.Question;
import com.ghy.mutiagent.model.TravelState;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/** 每轮上下文的唯一创建入口；只捕获事实，不调用模型、不修改会话。 */
@Component
public class TurnContextFactory {

    private final TurnTextNormalizer normalizer = new TurnTextNormalizer();

    public TurnContext create(TravelState state, String rawMessage, String requestId,
                              Map<String, Question> questionTemplates) {
        TurnContext turn = new TurnContext();
        turn.setTurnId("turn-" + UUID.randomUUID());
        turn.setRequestId(blankToNull(requestId));
        turn.setSessionId(state == null ? null : state.getSessionId());
        turn.setReceivedAt(LocalDateTime.now());
        turn.setStage(state == null ? null : state.getStage());
        turn.setCurrentField(state == null ? null : state.getCurrentField());
        Question current = state == null || state.getCurrentField() == null || questionTemplates == null
                ? null : questionTemplates.get(state.getCurrentField());
        turn.setCurrentQuestion(current == null ? null : current.getText());
        turn.setRawMessage(rawMessage == null ? "" : rawMessage);
        turn.setNormalizedMessage(normalizer.normalize(rawMessage));
        turn.setRequirementRevisionBefore(state == null || state.getRequirementSnapshot() == null
                ? 0 : state.getRequirementSnapshot().getRevision());
        turn.setConstraintRevisionBefore(state == null ? 0 : state.getConstraintRevision());
        turn.setPlanRevisionBefore(state == null ? 0 : state.getPlanRevision());
        return turn;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
