package com.ghy.mutiagent.observability.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * obs_run Mapper：upsert 语义——乱序不回退（RUNNING 事件不覆盖已有终态），
 * 终态字段 COALESCE 保留先到者，business/data 独立于 run 生命周期。
 */
public interface ObsRunMapper extends BaseMapper<ObsRun> {

    @Insert("INSERT INTO obs_run (run_id, operation_id, session_id, owner_id, run_status,"
            + " business_status, data_completeness, source_system, started_at, ended_at, duration_ms,"
            + " prompt_version, model_version, constraint_revision, snapshot_hash, created_at, updated_at) VALUES ("
            + "#{r.runId}, #{r.operationId}, #{r.sessionId}, #{r.ownerId}, #{r.runStatus},"
            + " #{r.businessStatus}, #{r.dataCompleteness}, #{r.sourceSystem}, #{r.startedAt}, #{r.endedAt},"
            + " #{r.durationMs}, #{r.promptVersion}, #{r.modelVersion}, #{r.constraintRevision}, #{r.snapshotHash},"
            + " NOW(3), NOW(3)) ON DUPLICATE KEY UPDATE"
            + " run_status = IF(VALUES(run_status) = 'RUNNING' AND run_status IS NOT NULL, run_status, VALUES(run_status)),"
            + " business_status = IF(VALUES(business_status) IS NULL, business_status, VALUES(business_status)),"
            + " data_completeness = IF(VALUES(data_completeness) = 'UNKNOWN' AND data_completeness IS NOT NULL,"
            + "   data_completeness, VALUES(data_completeness)),"
            + " source_system = IF(VALUES(source_system) = 'NEW' OR source_system = 'NEW', VALUES(source_system), source_system),"
            + " ended_at = IF(VALUES(ended_at) IS NULL, ended_at, VALUES(ended_at)),"
            + " duration_ms = IF(VALUES(duration_ms) IS NULL, duration_ms, VALUES(duration_ms)),"
            + " prompt_version = IF(VALUES(prompt_version) IS NULL, prompt_version, VALUES(prompt_version)),"
            + " model_version = IF(VALUES(model_version) IS NULL, model_version, VALUES(model_version)),"
            + " constraint_revision = IF(VALUES(constraint_revision) IS NULL, constraint_revision, VALUES(constraint_revision)),"
            + " snapshot_hash = IF(VALUES(snapshot_hash) IS NULL, snapshot_hash, VALUES(snapshot_hash)),"
            + " updated_at = NOW(3)")
    int upsert(@Param("r") ObsRun run);
}
