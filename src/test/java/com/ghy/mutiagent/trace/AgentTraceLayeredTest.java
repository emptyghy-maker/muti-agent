package com.ghy.mutiagent.trace;

import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * S12 分层状态与未知用量：provider/parse/validation 分开记录；
 * usage=null 是 UNKNOWN（不得被默认整型 0 消除），已知零用量是 KNOWN+0。
 */
class AgentTraceLayeredTest {

    @Test
    void successWithUnknownUsage() {
        AgentTrace t = AgentTrace.success("ItineraryAgent", 10, null);
        assertEquals(AgentTrace.SUCCESS, t.getProviderStatus());
        assertEquals(AgentTrace.USAGE_UNKNOWN, t.getUsageStatus(), "usage=null 必须记为 UNKNOWN");
        assertEquals(AgentTrace.NOT_ATTEMPTED, t.getParseStatus());
        assertEquals(AgentTrace.NOT_ATTEMPTED, t.getValidationStatus());
        assertNull(t.getFallbackReason());
    }

    @Test
    void successWithKnownZeroUsage() {
        AgentTrace t = AgentTrace.success("ItineraryAgent", 10,
                new TokenUsage(0, 0));
        assertEquals(AgentTrace.USAGE_KNOWN, t.getUsageStatus(), "已知零用量是 KNOWN");
        assertEquals(0, t.getTotalTokens());
    }

    @Test
    void failureMarksProviderFailedAndUsageUnknown() {
        AgentTrace t = AgentTrace.failure("ItineraryAgent", 10, "超时");
        assertEquals(AgentTrace.FAILED, t.getProviderStatus());
        assertEquals(AgentTrace.USAGE_UNKNOWN, t.getUsageStatus(), "失败调用也可能计费，用量必须为 UNKNOWN");
        assertFalse(t.isSuccess());
    }
}
