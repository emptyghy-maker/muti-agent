package com.ghy.mutiagent.observability.application;

import com.ghy.mutiagent.observability.application.ObsViews.PayloadView;
import com.ghy.mutiagent.observability.domain.ObsStatuses;
import com.ghy.mutiagent.observability.persistence.ObsPayloadRow;
import com.ghy.mutiagent.observability.persistence.ObsPayloadRowMapper;
import com.ghy.mutiagent.observability.persistence.ObsRun;
import com.ghy.mutiagent.observability.persistence.ObsRunMapper;
import com.ghy.mutiagent.observability.security.ObsScope;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** 载荷查询：重验关联 run 权限；原文第一版关闭（contentClosed=true），只返回元数据与状态 */
public final class ObsPayloadQueryService {

    private final ObsPayloadRowMapper payloadMapper;
    private final ObsRunMapper runMapper;

    public ObsPayloadQueryService(ObsPayloadRowMapper payloadMapper, ObsRunMapper runMapper) {
        this.payloadMapper = payloadMapper;
        this.runMapper = runMapper;
    }

    /** 无权限/不存在/隔离域统一返回 null（不探测资源） */
    public PayloadView payload(ObsScope scope, String payloadId) {
        ObsPayloadRow row = payloadMapper.selectById(payloadId);
        if (row == null) {
            return null;
        }
        ObsRun run = runMapper.selectById(row.getRunId());
        if (run == null || !scope.canSee(run.getOwnerId())) {
            return null;
        }
        boolean expired = ObsStatuses.PAYLOAD_EXPIRED.equals(row.getStatus());
        return new PayloadView(row.getPayloadId(), row.getRunId(), row.getKind(), row.getStatus(),
                row.getSizeBytes() == null ? 0 : row.getSizeBytes(), row.getDigest(),
                row.getCreatedAt() == null ? null : millis(row.getCreatedAt()),
                row.getExpiresAt() == null ? null : millis(row.getExpiresAt()),
                expired, true);
    }

    private static long millis(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
