package com.ghy.mutiagent.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * S12 span 注册表：跨线程记录 span/attempt 关联（spanId、parentSpanId、
 * operationId、owner、providerAttemptId、版本快照），供验收与报表
 * 检查父子归属、孤儿、重复、逻辑操作数与提供方尝试数。
 * 幂等重放不新增业务样本；SDK 重试必须成为额外 attempt（attemptId 递增）。
 */
public class TraceRegistry {

    private final ConcurrentHashMap<String, TraceContext> bySpanId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> attemptSeq = new ConcurrentHashMap<>();
    private final List<TraceContext> recorded = new CopyOnWriteArrayList<>();

    /** 记录一个 span（含 OPERATION 根 span 与 PROVIDER attempt span）；重复 spanId 会被如实记录 */
    public void register(TraceContext ctx) {
        if (ctx == null || ctx.getSpanId() == null) {
            return;
        }
        recorded.add(ctx);
        bySpanId.put(ctx.getSpanId(), ctx);
    }

    /** 下一个 provider attempt 序号（按 operation 递增；重试共享 operation 但 attemptId 不同） */
    public int nextAttemptId(String operationId) {
        return attemptSeq.computeIfAbsent(operationId, k -> new AtomicInteger()).incrementAndGet();
    }

    public TraceContext span(String spanId) {
        return bySpanId.get(spanId);
    }

    /** 全部原始 span 记录（含重复注册；观察方据此计算去重/孤儿/越权边） */
    public List<TraceContext> spans() {
        return List.copyOf(recorded);
    }

    /** 某 operation 的 PROVIDER attempt span（按 attemptId 稳定排序） */
    public List<TraceContext> attemptsOf(String operationId) {
        List<TraceContext> out = new ArrayList<>();
        for (TraceContext s : recorded) {
            if (operationId.equals(s.getOperationId()) && "PROVIDER".equals(s.getKind())) {
                out.add(s);
            }
        }
        out.sort((a, b) -> Integer.compare(
                a.getProviderAttemptId() == null ? 0 : a.getProviderAttemptId(),
                b.getProviderAttemptId() == null ? 0 : b.getProviderAttemptId()));
        return out;
    }
}
