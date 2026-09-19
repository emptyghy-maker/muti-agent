package com.ghy.mutiagent.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 目的地（首期：南京、苏州） */
@Data
@TableName("t_destination")
public class Destination {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String province;
    private String intro;
    private String tags;
    /** 人均日消费参考下限（默认预算计算用） */
    private BigDecimal dailyBudgetMin;
    /** 人均日消费参考上限 */
    private BigDecimal dailyBudgetMax;
    private Integer status;
}
