package com.ghy.mutiagent.trace;

import java.util.ArrayList;
import java.util.List;

/**
 * S12 追踪视图：对外（API/导出）的脱敏快照。
 * question/answer/error 统一经过 Redactor；不暴露原始 prompt/response 全文。
 */
public record TraceView(String sessionId, Long ownerId, String status, long durationMs,
                        String question, List<AgentView> agents) {

    public record AgentView(String agent, long durationMs, boolean success, String usageStatus,
                            Integer totalTokens, String providerStatus, String parseStatus,
                            String validationStatus, String fallbackReason,
                            String error, String answer) {
    }

    public static TraceView of(TraceContext ctx) {
        List<AgentView> views = new ArrayList<>();
        for (AgentTrace t : ctx.getAgents()) {
            views.add(new AgentView(t.getAgent(), t.getDurationMs(), t.isSuccess(),
                    t.getUsageStatus(),
                    AgentTrace.USAGE_KNOWN.equals(t.getUsageStatus()) ? t.getTotalTokens() : null,
                    t.getProviderStatus(), t.getParseStatus(), t.getValidationStatus(),
                    Redactor.mask(t.getFallbackReason()),
                    Redactor.mask(t.getError()), Redactor.mask(t.getAnswer())));
        }
        return new TraceView(ctx.getSessionId(), ctx.getOwnerId(), ctx.getStatus(),
                ctx.getTotalDurationMs(), Redactor.mask(ctx.getQuestion()), views);
    }
}
