package com.ghy.mutiagent.context.turn;

import com.ghy.mutiagent.model.enums.TravelStage;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 单个 HTTP 对话请求的临时上下文；通过方法参数传递，不序列化进 TravelState。 */
@Data
public class TurnContext {
    private String turnId;
    private String requestId;
    private String sessionId;
    private int turnIndex;
    private LocalDateTime receivedAt;
    private TravelStage stage;
    private String currentField;
    private String currentQuestion;
    private String rawMessage;
    private String normalizedMessage;
    private int requirementRevisionBefore;
    private int constraintRevisionBefore;
    private int planRevisionBefore;
    private List<TurnIntent> intents = new ArrayList<>();
    private TurnInterpretation interpretation;
    private ContextChangeSet changeSet;
}
