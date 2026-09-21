package com.ghy.mutiagent.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 酒店 */
@Data
@TableName("t_hotel")
public class Hotel {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long destinationId;
    private String name;
    /** 每晚价格 */
    private BigDecimal pricePerNight;
    private Double rating;
    /** 经济/舒适/高档/豪华 */
    private String level;
    private Double lng;
    private Double lat;
    private String features;
    /** 结构化标签（受控词表，逗号分隔）：需求匹配与冲突识别用 */
    private String tags;
    private Integer status;
    /** 街道/商圈级位置（网搜扩充酒店提供；KB 种子酒店可为空） */
    private String address;
    /** KB=知识库种子 / WEB_SEARCH=联网检索扩充（来源可追溯） */
    private String source;
    /** 来源会话标识（网搜酒店） */
    private String sourceRef;
    /** 来源说明（网搜匹配理由） */
    private String sourceNote;
    /** 推荐计数（被纳入候选池展示的不同会话数，POI 晋升口径） */
    private Integer recommendCount;
    /** 勾选计数（被用户确认选择的不同会话数，POI 晋升口径） */
    private Integer selectCount;
    /** 最近一次被推荐时间（跨会话复用新鲜度锚点） */
    private java.time.LocalDateTime lastRecommendedAt;
    /** 晋升为知识库的时间（KB_PROMOTED） */
    private java.time.LocalDateTime promotedAt;
}
