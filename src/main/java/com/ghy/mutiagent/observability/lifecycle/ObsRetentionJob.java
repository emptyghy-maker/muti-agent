package com.ghy.mutiagent.observability.lifecycle;

import com.ghy.mutiagent.observability.persistence.ObsPayloadRow;
import com.ghy.mutiagent.observability.persistence.ObsPayloadRowMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 观测数据保留期作业（开发文档 §15）：只处理 obs_ 数据与本模块创建的载荷；
 * 过期内容置 NULL + 状态 EXPIRED（摘要保留，UI 不显示无意义空白）；
 * 不清理业务表、P0 幂等记录或项目已有报告目录。手工调用（与 TraceRetentionJob 同模式）。
 */
public final class ObsRetentionJob {

    private final ObsPayloadRowMapper payloadMapper;

    public ObsRetentionJob(ObsPayloadRowMapper payloadMapper) {
        this.payloadMapper = payloadMapper;
    }

    /** 清理过期载荷；返回置为 EXPIRED 的 payloadId 列表 */
    public List<String> purge(int retentionDays) {
        LocalDateTime now = LocalDateTime.now();
        List<String> expired = new ArrayList<>();
        for (ObsPayloadRow row : payloadMapper.selectExpired(now)) {
            payloadMapper.expireContent(row.getPayloadId());
            expired.add(row.getPayloadId());
        }
        return expired;
    }

    public int previewExpiredCount() {
        return payloadMapper.selectExpired(LocalDateTime.now()).size();
    }
}
