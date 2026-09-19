package com.ghy.mutiagent.trace;

import lombok.Getter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import com.ghy.mutiagent.service.TimeSource;

/**
 * 一次任务的整条链路 trace：包含多个 Agent 的调用记录。
 *
 * 流式场景下，各 Agent 调用可能分布在多个线程执行，
 * 所以用 CopyOnWriteArrayList 保证多线程安全追加。
 *
 * S12：结束时间冻结——首次 finish 原子固定 endNs 与终态，之后重复读取 duration
 * 恒为终态值；重复 finish 不覆盖已确认终态、不追加重复终态事件。
 * 另携带 operation/span 关联字段（spanId、parentSpanId、attempt、版本快照），
 * 与业务结果（businessStatus）和持久化状态（persistenceStatus）分层记录。
 */
public class TraceContext {

    @Getter
    private final String sessionId;
    @Getter
    private final String question;
    private final TimeSource clock;
    private final long startNs;
    private final List<AgentTrace> agents = new CopyOnWriteArrayList<>();
    private final List<TraceEvent> events = new CopyOnWriteArrayList<>();
    @Getter
    private volatile String status = "RUNNING";
    private final AtomicLong endNs = new AtomicLong(-1);

    // ---- S12 关联字段 ----
    @Getter
    private volatile String spanId;
    @Getter
    private volatile String parentSpanId;
    @Getter
    private volatile String operationId;
    @Getter
    private volatile Long ownerId;
    @Getter
    private volatile Integer providerAttemptId;
    @Getter
    private volatile String kind;
    @Getter
    private volatile String promptVersion;
    @Getter
    private volatile String modelVersion;
    @Getter
    private volatile Long constraintRevision;
    @Getter
    private volatile String snapshotHash;
    @Getter
    private volatile String businessStatus;
    @Getter
    private volatile String persistenceStatus;

    public TraceContext(String sessionId, String question) {
        this(sessionId, question, TimeSource.SYSTEM);
    }

    public TraceContext(String sessionId, String question, TimeSource clock) {
        this.sessionId = sessionId;
        this.question = question;
        this.clock = clock == null ? TimeSource.SYSTEM : clock;
        this.startNs = this.clock.nanoTime();
    }

    public void add(AgentTrace trace) {
        agents.add(trace);
    }

    public void addEvent(TraceEvent event) {
        events.add(event);
    }

    public List<AgentTrace> getAgents() {
        return List.copyOf(agents);
    }

    public List<TraceEvent> getEvents() {
        return List.copyOf(events);
    }

    /** S12：终态事件数（来自事件存储；重复 finish 仍为 1） */
    public int terminalEventCount() {
        return (int) events.stream().filter(e -> "TERMINAL".equals(e.type())).count();
    }

    /** 未冻结时为当前耗时（RUNNING 进度），冻结后恒为终态耗时 */
    public long getTotalDurationMs() {
        long end = endNs.get();
        long now = end >= 0 ? end : clock.nanoTime();
        return (now - startNs) / 1_000_000;
    }

    public int getTotalInputTokens() {
        return agents.stream().mapToInt(AgentTrace::getInputTokens).sum();
    }

    public int getTotalOutputTokens() {
        return agents.stream().mapToInt(AgentTrace::getOutputTokens).sum();
    }

    public int getTotalTokens() {
        return agents.stream().mapToInt(AgentTrace::getTotalTokens).sum();
    }

    /** S12：首次 finish 原子冻结终态（endNs + status + 一条终态事件）；重复调用为幂等空操作 */
    public void finish(String status) {
        if (!endNs.compareAndSet(-1, clock.nanoTime())) {
            return;
        }
        this.status = status;
        events.add(TraceEvent.terminal(status, endNs.get()));
    }

    public void setSpanId(String spanId) {
        this.spanId = spanId;
    }

    public void setParentSpanId(String parentSpanId) {
        this.parentSpanId = parentSpanId;
    }

    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public void setProviderAttemptId(Integer providerAttemptId) {
        this.providerAttemptId = providerAttemptId;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public void setConstraintRevision(Long constraintRevision) {
        this.constraintRevision = constraintRevision;
    }

    public void setSnapshotHash(String snapshotHash) {
        this.snapshotHash = snapshotHash;
    }

    public void setBusinessStatus(String businessStatus) {
        this.businessStatus = businessStatus;
    }

    public void setPersistenceStatus(String persistenceStatus) {
        this.persistenceStatus = persistenceStatus;
    }
}
