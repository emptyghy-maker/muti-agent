package com.ghy.mutiagent.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 网搜 POI 入库审计（追加式）：每次联网检索结果入库前逐条记录 ACCEPT/REJECT 与拒绝原因，
 * 供追溯与事后审核（防恶意写入的审计证据链）。表名沿用 t_web_food_audit（历史兼容），
 * 通过 place_type 区分 FOOD / ATTRACTION / HOTEL。
 */
@Data
@TableName("t_web_food_audit")
public class WebFoodAudit {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private Long destinationId;
    private Long userId;
    /** FOOD / ATTRACTION / HOTEL（历史数据默认 FOOD） */
    private String placeType;
    /** 入库后的 POI 主键（ACCEPT 时回填；REJECT 为 null） */
    private Long placeId;
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
