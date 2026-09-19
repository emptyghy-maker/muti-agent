package com.ghy.mutiagent.observability.persistence;

import com.ghy.mutiagent.observability.collection.ObsPayloadStore;
import com.ghy.mutiagent.observability.domain.ObsStatuses;

import java.time.LocalDateTime;
import java.util.UUID;

/** 载荷 JDBC 存储：payloadId 随机生成；expires_at 由保留期配置换算（见生命周期作业） */
public final class JdbcObsPayloadStore implements ObsPayloadStore {

    private final ObsPayloadRowMapper mapper;
    private final long ttlDays;

    public JdbcObsPayloadStore(ObsPayloadRowMapper mapper, long ttlDays) {
        this.mapper = mapper;
        this.ttlDays = ttlDays;
    }

    @Override
    public String save(String runId, Long ownerId, String kind, String content,
                       String status, int sizeBytes, String digest) {
        try {
            ObsPayloadRow row = new ObsPayloadRow();
            row.setPayloadId("p-" + UUID.randomUUID());
            row.setRunId(runId);
            row.setOwnerId(ownerId);
            row.setKind(kind);
            row.setSizeBytes(sizeBytes);
            row.setStatus(status == null ? ObsStatuses.PAYLOAD_FULL : status);
            row.setContent(content);
            row.setDigest(digest);
            row.setCreatedAt(LocalDateTime.now());
            row.setExpiresAt(LocalDateTime.now().plusDays(Math.max(1, ttlDays)));
            mapper.insert(row);
            return row.getPayloadId();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
