package com.ghy.mutiagent.observability.collection;

import com.ghy.mutiagent.observability.domain.ObsEvent;
import com.ghy.mutiagent.observability.domain.TraceEnvelope;

/**
 * 安全观测出口（trace → observability 单向依赖）：
 * TraceService 在既有 register/finish 边界投递不可变快照；
 * 实现不得向业务传播异常，不得反向调用业务服务。
 */
public interface ObsEventSink {

    /** span 注册边界（快照冻结于调用时刻） */
    void onSpan(TraceEnvelope envelope);

    /** span 结束边界（OPERATION 根另发终态 + usage attempts） */
    void onFinished(TraceEnvelope envelope);

    /** 直接事件投递（业务薄埋点/对账/导入共用） */
    void record(ObsEvent event);

    /** 载荷入库：返回 payloadId（实现失败时返回 null，不抛出） */
    String storePayload(String runId, Long ownerId, String kind, String raw);

    static ObsEventSink noop() {
        return Noop.INSTANCE;
    }

    enum Noop implements ObsEventSink {
        INSTANCE;

        @Override
        public void onSpan(TraceEnvelope envelope) {
        }

        @Override
        public void onFinished(TraceEnvelope envelope) {
        }

        @Override
        public void record(ObsEvent event) {
        }

        @Override
        public String storePayload(String runId, Long ownerId, String kind, String raw) {
            return null;
        }
    }
}
