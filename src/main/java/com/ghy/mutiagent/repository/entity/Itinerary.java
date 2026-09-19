package com.ghy.mutiagent.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 行程（含版本链，支持 ADJUST 修改模式） */
@Data
@TableName("t_itinerary")
public class Itinerary {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long destinationId;
    private String title;
    private Integer days;
    private BigDecimal totalBudget;
    private BigDecimal totalCost;
    /** 用户偏好快照 JSON */
    private String preferenceJson;
    /** 每日节点 JSON（前端时间线 + 超链接渲染） */
    private String planJson;
    private String status;
    private Integer version;
    /** 上一版本 itinerary_id（首版为空） */
    private Long parentId;
    /** 生成该版本的操作 ID（S06-B：同一操作最多一次业务提交） */
    private String operationId;
    private LocalDateTime createdAt;
}
