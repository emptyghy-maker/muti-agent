package com.ghy.mutiagent.context.baseline;

import java.util.concurrent.atomic.AtomicBoolean;

/** Recorder 在一次调用期间持有的可变观测句柄；业务代码不得读取它作决策。 */
public final class ContextObservation {

    private static final ContextObservation DISABLED = new ContextObservation(null, null, false);

    private final ContextCaptureIdentity identity;
    private final ContextManifest manifest;
    private final boolean enabled;
    private final AtomicBoolean emitted = new AtomicBoolean();
    private Integer providerInputTokens;
    private Integer providerOutputTokens;
    private long providerDurationMs;
    private String providerStatus = "NOT_ATTEMPTED";

    ContextObservation(ContextCaptureIdentity identity, ContextManifest manifest, boolean enabled) {
        this.identity = identity;
        this.manifest = manifest;
        this.enabled = enabled;
    }

    public static ContextObservation disabled() {
        return DISABLED;
    }

    public boolean enabled() {
        return enabled;
    }

    ContextCaptureIdentity identity() {
        return identity;
    }

    ContextManifest manifest() {
        return manifest;
    }

    Integer providerInputTokens() {
        return providerInputTokens;
    }

    void setProviderInputTokens(Integer providerInputTokens) {
        this.providerInputTokens = providerInputTokens;
    }

    Integer providerOutputTokens() {
        return providerOutputTokens;
    }

    void setProviderOutputTokens(Integer providerOutputTokens) {
        this.providerOutputTokens = providerOutputTokens;
    }

    long providerDurationMs() {
        return providerDurationMs;
    }

    void setProviderDurationMs(long providerDurationMs) {
        this.providerDurationMs = providerDurationMs;
    }

    String providerStatus() {
        return providerStatus;
    }

    void setProviderStatus(String providerStatus) {
        this.providerStatus = providerStatus;
    }

    boolean markEmitted() {
        return emitted.compareAndSet(false, true);
    }

    /** 观测句柄只保留关联键，不持有包含大 sections 的请求对象。 */
    record ContextCaptureIdentity(String sessionId, Long ownerId, String operationId) {
        static ContextCaptureIdentity from(ContextCaptureRequest request) {
            return new ContextCaptureIdentity(request.sessionId(), request.ownerId(), request.operationId());
        }
    }
}
