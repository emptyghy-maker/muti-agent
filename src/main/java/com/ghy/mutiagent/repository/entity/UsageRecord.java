package com.ghy.mutiagent.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用量记录：用户操作日志 + 各阶段 Agent 的 token 消耗 + Q&A 审计流水（成本核算）。
 * 纯操作（对话/确认/翻页/路径）token 为 0；Agent 调用记录输入/输出 token、模型与原始输出。
 */
@Data
@TableName("t_usage_record")
public class UsageRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String username;
    private String sessionId;
    /** 阶段：PREFERENCE / ATTRACTIONS / FOODS / HOTELS / ITINERARY / ADJUST / ROUTE / FEEDBACK */
    private String stage;
    /** 动作：创建会话 / 对话 / 确认景点 / 换一批 / 偏好解析 / 行程规划 / 路径推荐 / 提交评价… */
    private String action;
    /** Agent 名（纯操作时为空） */
    private String agent;
    /** 本次调用的模型名（纯操作时为空） */
    private String model;
    /** 渠道：KB 知识库 / CACHE 缓存翻页 / AGENT AI 分析 / RULE_FALLBACK 规则兜底 / OP 纯操作 */
    private String channel;
    /** 用户原始问题（对话轮次） */
    private String question;
    /** 回复文本或 LLM 原始输出（截断存储） */
    private String answer;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private Long durationMs;
    private String status;
    private String remark;
    private LocalDateTime createdAt;
    /** 行级预估费用（查询时计算，不落库） */
    @TableField(exist = false)
    private BigDecimal cost;
    /** 慢调用「已解决」标记（查询时关联 t_slow_resolved 回填，不落库） */
    @TableField(exist = false)
    private Boolean resolved;
    @TableField(exist = false)
    private String resolvedBy;
    @TableField(exist = false)
    private String resolvedNote;
}
