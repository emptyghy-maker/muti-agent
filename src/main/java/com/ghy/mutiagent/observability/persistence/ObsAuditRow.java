package com.ghy.mutiagent.observability.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** obs_audit：管理动作审计（ADMIN 跨用户查询/导入/导出） */
@Data
@TableName("obs_audit")
public class ObsAuditRow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long actorId;
    private String actorUsername;
    private String action;
    private String targetType;
    private String targetId;
    private String detail;
    private LocalDateTime createdAt;
}
