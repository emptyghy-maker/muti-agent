package com.ghy.mutiagent.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 网搜美食入库审计（追加式）：每次联网检索结果入库前逐条记录 ACCEPT/REJECT 与拒绝原因，
 * 供追溯与事后审核（防恶意写入的审计证据链）。
 */
@Data
@TableName("t_web_food_audit")
public class WebFoodAudit {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private Long destinationId;
    private Long userId;
    private String name;
    private String cuisine;
    private BigDecimal avgPrice;
    private String address;
    /** ACCEPT=已校验入库 / REJECT=拒绝 */
    private String action;
    private String rejectReason;
    private String rawPayload;
    private LocalDateTime createdAt;
}
