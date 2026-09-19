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
}
