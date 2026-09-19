package com.ghy.mutiagent.observability.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** obs_change_revision：变更修订（追加，禁止覆盖历史） */
@Data
@TableName("obs_change_revision")
public class ObsChangeRevision {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Integer revisionNo;
    private String promptVersion;
    private String modelVersion;
    private String gitHash;
    private String changes;
    private String rationale;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
}
