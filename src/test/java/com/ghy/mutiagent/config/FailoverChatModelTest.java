package com.ghy.mutiagent.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FailoverChatModelTest {

    private final ChatRequest request = ChatRequest.builder()
            .messages(UserMessage.from("test"))
            .build();

    @AfterEach
    void clearContext() {
        LlmRouteContext.clear();
    }

    @Test
    void quotaErrorFallsBackAndCircuitSkipsExhaustedModel() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger backupCalls = new AtomicInteger();
        ChatModel primary = failing(primaryCalls, "AllocationQuota.FreeTierOnly: Free quota exhausted");
        ChatModel backup = successful(backupCalls, "backup");
        FailoverChatModel model = new FailoverChatModel("fast", List.of(
                new FailoverChatModel.Candidate("primary", primary),
                new FailoverChatModel.Candidate("backup", backup)),
                "FAILOVER", 60_000, false);

        assertEquals("backup", model.chat(request).aiMessage().text());
        assertEquals("backup", LlmRouteContext.consume("fast", "none"));
        assertEquals("backup", model.chat(request).aiMessage().text());
        assertEquals(1, primaryCalls.get(), "额度耗尽模型在冷却期内不应再次请求");
        assertEquals(2, backupCalls.get());
    }

    @Test
    void fixedModeNeverUsesBackup() {
        AtomicInteger backupCalls = new AtomicInteger();
        FailoverChatModel model = new FailoverChatModel("eval", List.of(
                new FailoverChatModel.Candidate("fixed", failing(new AtomicInteger(),
                        "AllocationQuota.FreeTierOnly")),
                new FailoverChatModel.Candidate("backup", successful(backupCalls, "backup"))),
                "FIXED", 60_000, false);

        assertThrows(RuntimeException.class, () -> model.chat(request));
        assertEquals(0, backupCalls.get(), "评测固定模式禁止换模型");
    }

    @Test
    void promptOrValidationLikeErrorDoesNotChangeModel() {
        AtomicInteger backupCalls = new AtomicInteger();
        FailoverChatModel model = new FailoverChatModel("fast", List.of(
                new FailoverChatModel.Candidate("primary", failing(new AtomicInteger(),
                        "JSON schema validation failed")),
                new FailoverChatModel.Candidate("backup", successful(backupCalls, "backup"))),
                "FAILOVER", 60_000, false);

        assertThrows(RuntimeException.class, () -> model.chat(request));
        assertEquals(0, backupCalls.get(), "业务输出问题必须交给校验/修复，不能偷偷换模型");
    }

    @Test
    void selectedDelegateKeepsItsOwnPhysicalModelName() {
        ChatModel physical = new ChatModel() {
            @Override
            public ChatRequestParameters defaultRequestParameters() {
                return ChatRequestParameters.builder().modelName("physical-model").build();
            }

            @Override
            public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(request.parameters().modelName()))
                        .build();
            }
        };
        FailoverChatModel model = new FailoverChatModel("fast", List.of(
                new FailoverChatModel.Candidate("physical-model", physical)),
                "FAILOVER", 60_000, false);

        assertEquals("physical-model", model.chat(request).aiMessage().text());
    }

    private static ChatModel failing(AtomicInteger calls, String message) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                calls.incrementAndGet();
                throw new RuntimeException(message);
            }
        };
    }

    private static ChatModel successful(AtomicInteger calls, String answer) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                calls.incrementAndGet();
                return ChatResponse.builder().aiMessage(AiMessage.from(answer)).build();
            }
        };
    }
}
