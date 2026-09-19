package com.ghy.mutiagent.observability.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** obs_optimization_task：优化任务（人工录入；ACCEPTED 只表示人工审核通过） */
@Data
@TableName("obs_optimization_task")
public class ObsOptimizationTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String goal;
    private String hypothesis;
    private String scope;
    private String acceptance;
    private String nonGoals;
    private String status;
    private String conclusion;
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
