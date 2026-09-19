package com.ghy.mutiagent.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 美食店铺 */
@Data
@TableName("t_restaurant")
public class Restaurant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long destinationId;
    private String name;
    /** 风味：本地菜/火锅/小吃/杭帮菜/川菜/素斋… */
    private String cuisine;
    private String signatureDish;
    /** 结构化标签（受控词表，逗号分隔）：需求匹配与冲突识别用 */
    private String tags;
    /** 人均（元） */
    private BigDecimal avgPrice;
    private Double lng;
    private Double lat;
    private Double rating;
    private String businessHours;
    private Integer status;
}
