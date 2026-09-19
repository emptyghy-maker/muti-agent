package com.ghy.mutiagent.repository.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话写操作（S06-B）：requestId 幂等、attemptNo 执行权栅栏、VALIDATED 草案复用与恢复依据。
 * 状态机：RUNNING → VALIDATED → COMPLETED；RUNNING → FAILED / UNKNOWN。
 * 同一 actor+requestId 唯一；requestHash 为服务端对规范化请求计算的 SHA-256。
 */
@Data
@TableName("t_travel_operation")
public class TravelOperation {
    @TableId
    private String id;
    private Long userId;
    private String sessionId;
    private String requestId;
    private String action;
    private String requestHash;
    private Long baseRevision;
    private String status;
    private Integer attemptNo;
    private LocalDateTime leaseUntil;
    /** 提供方已被调用的时间戳：有调用但无 VALIDATED 草案 = 崩溃恢复中的 UNKNOWN，禁止自动重放 */
    private LocalDateTime providerAttemptAt;
    private String inputSnapshot;
    private String validatedDraft;
    private String resultJson;
    private Long itineraryId;
    private String errorCode;
    /** 面向用户的错误详情（如 422 violations 文案），不含内部 prompt/异常栈 */
    private String errorDetail;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
