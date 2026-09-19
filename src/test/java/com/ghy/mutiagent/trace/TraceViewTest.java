package com.ghy.mutiagent.trace;

import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * S12 视图脱敏：question/answer/error 三个字段在 API 视图出口不泄露合成标记。
 */
class TraceViewTest {

    @Test
    void viewMasksQuestionAnswerAndError() {
        TraceContext ctx = new TraceContext("s", "问题里带 TEST_SECRET_TOKEN_8D2 请规划");
        ctx.setOwnerId(1L);
        AgentTrace ok = AgentTrace.success("ItineraryAgent", 10, new TokenUsage(1, 1));
        ok.setAnswer("模型输出含手机号 13800138000");
        ctx.add(ok);
        AgentTrace fail = AgentTrace.failure("ItineraryRepairAgent", 5,
                "异常信息 person@example.invalid 连接失败");
        ctx.add(fail);
        ctx.finish("FAILED");

        TraceView view = TraceView.of(ctx);
        assertFalse(Redactor.containsSensitive(view.question()));
        for (TraceView.AgentView a : view.agents()) {
            assertFalse(Redactor.containsSensitive(String.valueOf(a.error())));
            assertFalse(Redactor.containsSensitive(String.valueOf(a.answer())));
        }
    }
}
