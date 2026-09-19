package com.ghy.mutiagent.observability.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** obs_run：运行记录（生命周期/业务/完整性三分，见 sql/observability/001_core_schema.sql） */
@Data
@TableName("obs_run")
public class ObsRun {
    @TableId(type = IdType.INPUT)
    private String runId;
    private String operationId;
    private String sessionId;
    private Long ownerId;
    private String runStatus;
    private String businessStatus;
    private String dataCompleteness;
    private String sourceSystem;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long durationMs;
    private String promptVersion;
    private String modelVersion;
    private Long constraintRevision;
    private String snapshotHash;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
