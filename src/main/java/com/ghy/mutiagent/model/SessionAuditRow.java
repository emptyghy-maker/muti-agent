package com.ghy.mutiagent.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 会话维度审计行：把一次规划的所有用量记录聚合为一行（管理员审计台） */
@Data
public class SessionAuditRow {
    private String sessionId;
    private String username;
    /** 目的地名（来自会话快照；调整子任务等无快照时为 null） */
    private String destinationName;
    /** 调整子任务会话（adj- 前缀，由行程调整流程产生） */
    private boolean subTask;
    private int recordCount;
    private int inputTokens;
    private int outputTokens;
    private int totalTokens;
    private BigDecimal cost;
    private LocalDateTime firstAt;
    private LocalDateTime lastAt;
    /** 最近一条记录所属阶段 */
    private String lastStage;
    /**
     * 会话结果：DONE 成功（行程已发布） / TIMEOUT 失败·超时 / FAILED 失败 /
     * RESTARTED 未完成·重新开始 / EXPIRED 未完成·会话过期 / ONGOING 未完成·进行中
     */
    private String resultStatus;
    /** 失败原因摘要（最近一条失败记录的备注，截断） */
    private String failRemark;
    /** 失败步骤（失败记录的动作，如「行程规划」） */
    private String failAction;
    /** 失败记录所属阶段 */
    private String failStage;
    /** AI 调用总耗时（毫秒，仅模型调用行求和） */
    private long totalAgentMs;
    /** 行程调整次数 */
    private int adjustCount;
}
