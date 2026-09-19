package com.ghy.mutiagent.service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * S11 操作取消注册表：持久化的取消标记在 t_travel_operation.status（CANCELLED），
 * 本注册表向在途执行线程提供进程内信号——晚到结果在提交/修复前被取消检查拒绝。
 * 显式取消与 SSE 断开走同一条取消路径。
 */
public class CancelRegistry {

    /** 每操作一个取消令牌：执行线程在 验证前/修复前/提交前 检查 */
    public static final class CancelToken {
        private volatile boolean cancelled;

        public void cancel() {
            cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    private final ConcurrentHashMap<String, CancelToken> tokens = new ConcurrentHashMap<>();

    public CancelToken token(String operationId) {
        return tokens.computeIfAbsent(operationId, k -> new CancelToken());
    }

    public void cancel(String operationId) {
        CancelToken t = tokens.get(operationId);
        if (t != null) {
            t.cancel();
        }
    }

    public void forget(String operationId) {
        tokens.remove(operationId);
    }
}
