package com.ghy.mutiagent.observability.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** obs_run_link：任务↔run 关联（BASELINE/CANDIDATE/RELATED） */
@Data
@TableName("obs_run_link")
public class ObsRunLink {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private String runId;
    private String role;
    private LocalDateTime createdAt;
}
