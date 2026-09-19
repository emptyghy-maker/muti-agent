package com.ghy.mutiagent.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** S12 已知导出副本：保留期清理时随明细一并删除（不残留原文副本） */
@Data
@TableName("t_trace_export")
public class TraceExportCopy {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String detailId;
    private String rawJson;
}
