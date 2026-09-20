package com.ghy.mutiagent.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 慢调用「已解决」标记：管理员对 t_usage_record 中已处理的慢调用行打标，
 * 默认排行不再展示，可切换查看与恢复（可操控的告警闭环）。
 */
@Data
@TableName("t_slow_resolved")
public class SlowResolved {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long usageRecordId;
    private String resolvedBy;
    private String note;
    private LocalDateTime createdAt;
}
