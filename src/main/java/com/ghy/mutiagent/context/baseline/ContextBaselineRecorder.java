package com.ghy.mutiagent.context.baseline;

import dev.langchain4j.model.output.TokenUsage;

/** 上下文观测统一入口；所有实现都必须旁路失败，不能改变业务结果。 */
public interface ContextBaselineRecorder {

    ContextObservation begin(ContextCaptureRequest request);

    void providerFinished(ContextObservation observation, TokenUsage usage, long durationMs, String status);

    void parseFinished(ContextObservation observation, String parseStatus,
                       String validationStatus, String errorCode);

    static ContextBaselineRecorder noop() {
        return NoopHolder.INSTANCE;
    }

    final class NoopHolder {
        private static final ContextBaselineRecorder INSTANCE = new ContextBaselineRecorder() {
            @Override
            public ContextObservation begin(ContextCaptureRequest request) {
                return ContextObservation.disabled();
            }

            @Override
            public void providerFinished(ContextObservation observation, TokenUsage usage,
                                         long durationMs, String status) {
            }

            @Override
            public void parseFinished(ContextObservation observation, String parseStatus,
                                      String validationStatus, String errorCode) {
            }
        };

        private NoopHolder() {
        }
    }
}
