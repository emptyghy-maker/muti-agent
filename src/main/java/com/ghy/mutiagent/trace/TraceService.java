package com.ghy.mutiagent.trace;

import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.observability.collection.ObsEventSink;
import com.ghy.mutiagent.observability.domain.TraceEnvelope;
import com.ghy.mutiagent.service.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Agent 调用 trace 服务：记录每次问数的链路（耗时 + token），
 * 完成后打一份汇总日志到控制台，并保留在内存环形缓冲里供接口查询。
 *
 * S12：可注入单调时钟（测试可控推进）；span 注册表（跨线程关联）；
 * 查询可按 owner 过滤（普通用户只能看自己的链路）。
 */
@Service
public class TraceService {

    private static final Logger log = LoggerFactory.getLogger(TraceService.class);
    private static final int MAX_RECENT = 200;

    private final ConcurrentLinkedDeque<TraceContext> recent = new ConcurrentLinkedDeque<>();
    private TimeSource timeSource = TimeSource.SYSTEM;
    private TraceRegistry registry;
    /** Observability 安全观测出口（默认 noop；快照冻结于调用时刻，异常不传播） */
    private volatile ObsEventSink obsEventSink = ObsEventSink.noop();

    /** S12：可注入时钟（可控时钟推进不依赖睡眠）；未设置时用系统单调时钟 */
    public void setTimeSource(TimeSource timeSource) {
        this.timeSource = timeSource == null ? TimeSource.SYSTEM : timeSource;
    }

    /** Observability：装配观测出口（模块关闭时为 noop，本服务签名与原行为不变） */
    public void setObsEventSink(ObsEventSink sink) {
        this.obsEventSink = sink == null ? ObsEventSink.noop() : sink;
    }

    /** S12：span 注册表装配（可选）；未装配时注册为空操作 */
    public void setTraceRegistry(TraceRegistry registry) {
        this.registry = registry;
    }

    public TraceRegistry registry() {
        return registry;
    }

    public TraceContext newTrace(String sessionId, String question) {
        return new TraceContext(sessionId, question, timeSource);
    }

    /** S12：把 span 写入注册表（OPERATION 根 span 与 PROVIDER attempt span 在创建时登记） */
    public void register(TraceContext ctx) {
        if (registry != null) {
            registry.register(ctx);
        }
        try {
            obsEventSink.onSpan(TraceEnvelope.of(ctx));
        } catch (RuntimeException ignored) {
            // 观测出口失败不影响原注册语义
        }
    }

    /** 链路结束时调用：打印汇总日志、放入环形缓冲（终态由调用方先设置） */
    public void finish(TraceContext ctx) {
        logSummary(ctx);
        recent.addFirst(ctx);
        while (recent.size() > MAX_RECENT) {
            recent.pollLast();
        }
        try {
            obsEventSink.onFinished(TraceEnvelope.of(ctx));
        } catch (RuntimeException ignored) {
            // 观测出口失败不影响原结束语义
        }
    }

    public List<TraceContext> recent(int limit) {
        return recent.stream().limit(Math.max(0, limit)).toList();
    }

    /** S12：owner 过滤——普通用户只能看到自己的链路（列表/详情/导出同一口径） */
    public List<TraceContext> recentForOwner(Long ownerId, int limit) {
        return recent.stream()
                .filter(t -> t.getOwnerId() == null || t.getOwnerId().equals(ownerId))
                .limit(Math.max(0, limit))
                .toList();
    }

    /** S12：按 owner 查看指定会话的链路详情；无归属可见记录 → FORBIDDEN（不区分不存在与无权，防探测） */
    public TraceContext detail(Long ownerId, String sessionId) {
        for (TraceContext t : recent) {
            if (sessionId.equals(t.getSessionId())) {
                if (t.getOwnerId() != null && t.getOwnerId().equals(ownerId)) {
                    return t;
                }
                throw new BizException(ResultCode.FORBIDDEN);
            }
        }
        throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
    }

    /** S12：owner 视角的脱敏视图（API 出口） */
    public List<TraceView> viewsFor(Long ownerId, int limit) {
        return recentForOwner(ownerId, limit).stream().map(TraceView::of).toList();
    }

    /** O1.3：provider 工作量 = 该 operation 的 PROVIDER 叶子 span 耗时之和（父/OPERATION span 不重复计） */
    public long providerWorkloadMs(String operationId) {
        if (registry == null || operationId == null) {
            return 0L;
        }
        return registry.attemptsOf(operationId).stream()
                .mapToLong(TraceContext::getTotalDurationMs)
                .sum();
    }

    /** S12：受控导出（EXPORT 出口）——同一 owner 过滤 + 同一脱敏口径 */
    public List<TraceView> exportFor(Long ownerId, int limit) {
        return viewsFor(ownerId, limit);
    }

    /** S12 日志出口：question/error/answer 统一脱敏后输出（LOG 口径与 API/EXPORT 一致） */
    public String summaryText(TraceContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("[TRACE] 问题=").append(Redactor.mask(ctx.getQuestion()))
                .append(" 状态=").append(ctx.getStatus())
                .append(" 总耗时=").append(ctx.getTotalDurationMs()).append("ms");
        for (AgentTrace t : ctx.getAgents()) {
            if (t.isSuccess()) {
                sb.append(" | ").append(t.getAgent())
                        .append(" 耗时=").append(t.getDurationMs()).append("ms")
                        .append(" 输出=").append(Redactor.mask(t.getAnswer()));
            } else {
                sb.append(" | ").append(t.getAgent())
                        .append(" 失败 耗时=").append(t.getDurationMs()).append("ms")
                        .append(" 原因=").append(Redactor.mask(t.getError()));
            }
        }
        return sb.toString();
    }

    private void logSummary(TraceContext ctx) {
        log.info(summaryText(ctx));
    }
}
