package com.ghy.mutiagent.observability.collection;

import com.ghy.mutiagent.observability.domain.ObsEvent;

/**
 * 观测载荷存储边界：collection 不依赖持久化实现；JDBC 实现在 persistence 包。
 * 失败返回 null 且不抛出（观测失败不重试业务）。
 */
public interface ObsPayloadStore {

    /**
     * 保存净化后的载荷。
     *
     * @return payloadId（失败 null）
     */
    String save(String runId, Long ownerId, String kind, String content,
                String status, int sizeBytes, String digest);
}
