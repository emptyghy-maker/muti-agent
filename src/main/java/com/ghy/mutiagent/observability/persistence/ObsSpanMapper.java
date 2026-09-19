package com.ghy.mutiagent.observability.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/** obs_span Mapper：upsert 语义——STARTED 不覆盖已终态，终态字段保留先到者 */
public interface ObsSpanMapper extends BaseMapper<ObsSpan> {

    @Insert("INSERT INTO obs_span (span_id, run_id, parent_span_id, node_execution_id, kind, agent,"
            + " attempt_no, status, started_at, ended_at, duration_ms, summary, payload_ref, created_at) VALUES ("
            + "#{s.spanId}, #{s.runId}, #{s.parentSpanId}, #{s.nodeExecutionId}, #{s.kind}, #{s.agent},"
            + " #{s.attemptNo}, #{s.status}, #{s.startedAt}, #{s.endedAt}, #{s.durationMs}, #{s.summary},"
            + " #{s.payloadRef}, NOW(3)) ON DUPLICATE KEY UPDATE"
            + " status = IF(VALUES(status) = 'STARTED' AND status IS NOT NULL AND status <> 'STARTED', status, VALUES(status)),"
            + " ended_at = IF(VALUES(ended_at) IS NULL, ended_at, VALUES(ended_at)),"
            + " duration_ms = IF(VALUES(duration_ms) IS NULL, duration_ms, VALUES(duration_ms)),"
            + " agent = IF(VALUES(agent) IS NULL, agent, VALUES(agent)),"
            + " summary = IF(VALUES(summary) IS NULL, summary, VALUES(summary))")
    int upsert(@Param("s") ObsSpan span);
}
