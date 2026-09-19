package com.ghy.mutiagent.observability.domain;

import com.ghy.mutiagent.trace.AgentTrace;
import com.ghy.mutiagent.trace.TraceContext;
import com.ghy.mutiagent.trace.TraceEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * TraceContext 不可变快照：把正在变化的 TraceContext 冻结为纯数据，
 * 供异步队列/批写线程安全读取（开发文档 §7.2）。
 */
public final class TraceEnvelope {

    private final String sessionId;
    private final String question;
    private final String status;
    private final String spanId;
    private final String parentSpanId;
    private final String operationId;
    private final Long ownerId;
    private final Integer providerAttemptId;
    private final String kind;
    private final String promptVersion;
    private final String modelVersion;
    private final Long constraintRevision;
    private final String snapshotHash;
    private final String businessStatus;
    private final String persistenceStatus;
    private final long durationMs;
    private final int terminalEventCount;
    private final List<AgentTrace> agents;
    private final List<TraceEvent> events;

    private TraceEnvelope(TraceContext ctx) {
        this.sessionId = ctx.getSessionId();
        this.question = ctx.getQuestion();
        this.status = ctx.getStatus();
        this.spanId = ctx.getSpanId();
        this.parentSpanId = ctx.getParentSpanId();
        this.operationId = ctx.getOperationId();
        this.ownerId = ctx.getOwnerId();
        this.providerAttemptId = ctx.getProviderAttemptId();
        this.kind = ctx.getKind();
        this.promptVersion = ctx.getPromptVersion();
        this.modelVersion = ctx.getModelVersion();
        this.constraintRevision = ctx.getConstraintRevision();
        this.snapshotHash = ctx.getSnapshotHash();
        this.businessStatus = ctx.getBusinessStatus();
        this.persistenceStatus = ctx.getPersistenceStatus();
        this.durationMs = ctx.getTotalDurationMs();
        this.terminalEventCount = ctx.terminalEventCount();
        this.agents = ctx.getAgents() == null ? List.of() : List.copyOf(ctx.getAgents());
        this.events = ctx.getEvents() == null ? List.of() : List.copyOf(ctx.getEvents());
    }

    public static TraceEnvelope of(TraceContext ctx) {
        return ctx == null ? null : new TraceEnvelope(ctx);
    }

    public String sessionId() {
        return sessionId;
    }

    public String question() {
        return question;
    }

    public String status() {
        return status;
    }

    public String spanId() {
        return spanId;
    }

    public String parentSpanId() {
        return parentSpanId;
    }

    public String operationId() {
        return operationId;
    }

    public Long ownerId() {
        return ownerId;
    }

    public Integer providerAttemptId() {
        return providerAttemptId;
    }

    public String kind() {
        return kind;
    }

    public String promptVersion() {
        return promptVersion;
    }

    public String modelVersion() {
        return modelVersion;
    }

    public Long constraintRevision() {
        return constraintRevision;
    }

    public String snapshotHash() {
        return snapshotHash;
    }

    public String businessStatus() {
        return businessStatus;
    }

    public String persistenceStatus() {
        return persistenceStatus;
    }

    public long durationMs() {
        return durationMs;
    }

    public int terminalEventCount() {
        return terminalEventCount;
    }

    public List<AgentTrace> agents() {
        return agents;
    }

    public List<TraceEvent> events() {
        return events;
    }

    /** 摘要（脱敏，供 digest 与查询展示）：不含问题/答案原文 */
    public List<String> summaryLines() {
        List<String> lines = new ArrayList<>();
        lines.add("span=" + spanId);
        lines.add("kind=" + kind);
        lines.add("status=" + status);
        lines.add("business=" + businessStatus);
        lines.add("persist=" + persistenceStatus);
        return lines;
    }
}
