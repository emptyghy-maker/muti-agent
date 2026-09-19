package com.ghy.mutiagent.service.eval;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 提示词/模型版本管理与回退（手册 §5.3）：提示词作为版本化资源管理，模型和参数
 * 一起形成配置快照；operation 启动时绑定版本，不受中途配置切换影响，新操作才用新版本。
 *
 * ACTIVATE/ROLLBACK 仅操作隔离环境（testEnvironmentOnly），不发布生产；
 * 每次切换记录审计动作，保留变更理由与实验 ID 由调用方随审计写入。
 */
public final class PromptVersionManager {

    /** 审计条目：动作 + 目标版本 + 时刻 */
    public record AuditEntry(String action, String version, long atMs) {
    }

    /** 操作版本绑定：operation 启动时刻的版本快照 */
    public record OperationBinding(String operationId, String version, long atMs) {
    }

    private final String initialVersion;
    private volatile String activeVersion;
    private final Deque<String> rollbackStack = new ArrayDeque<>();
    private final List<AuditEntry> audit = new CopyOnWriteArrayList<>();
    private final Map<String, OperationBinding> bindings = new ConcurrentHashMap<>();

    public PromptVersionManager(String initialVersion) {
        if (initialVersion == null || initialVersion.isBlank()) {
            throw new IllegalArgumentException("初始版本不能为空");
        }
        this.initialVersion = initialVersion;
        this.activeVersion = initialVersion;
    }

    public String activeVersion() {
        return activeVersion;
    }

    /**
     * 隔离环境版本切换：记录审计，入回退栈。
     * 非隔离环境（生产）拒绝直接切换——必须先离线报告 + 人工审查 + 既有发布流程。
     */
    public synchronized void activate(String version, boolean testEnvironmentOnly) {
        if (!testEnvironmentOnly) {
            throw new IllegalStateException("非隔离环境禁止直接切换版本，请走人工审查发布流程");
        }
        if (version == null || version.isBlank() || version.equals(activeVersion)) {
            return;
        }
        rollbackStack.push(activeVersion);
        activeVersion = version;
        audit.add(new AuditEntry("ACTIVATE", version, System.currentTimeMillis()));
    }

    /** 回退到最近一次激活前的版本；无历史时拒绝 */
    public synchronized void rollback() {
        if (rollbackStack.isEmpty()) {
            throw new IllegalStateException("没有可回退的版本历史");
        }
        String prev = rollbackStack.pop();
        activeVersion = prev;
        audit.add(new AuditEntry("ROLLBACK", prev, System.currentTimeMillis()));
    }

    /** operation 启动时绑定当前版本：进行中操作不受后续切换影响 */
    public String bind(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId 不能为空");
        }
        OperationBinding binding = new OperationBinding(operationId, activeVersion,
                System.currentTimeMillis());
        bindings.put(operationId, binding);
        return binding.version();
    }

    /** 查询某 operation 启动时绑定的版本（未绑定返回 null） */
    public String boundVersion(String operationId) {
        OperationBinding binding = bindings.get(operationId);
        return binding == null ? null : binding.version();
    }

    public List<String> auditActions() {
        List<String> actions = new ArrayList<>();
        for (AuditEntry e : audit) {
            actions.add(e.action());
        }
        return actions;
    }

    public List<Map<String, Object>> auditEntries() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AuditEntry e : audit) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", e.action());
            m.put("version", e.version());
            m.put("atMs", e.atMs());
            out.add(m);
        }
        return out;
    }

    public String initialVersion() {
        return initialVersion;
    }
}
