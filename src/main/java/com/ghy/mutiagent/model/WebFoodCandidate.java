package com.ghy.mutiagent.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 联网检索的美食候选（知识库外，阶段2）。
 * 仅作参考展示：信息来自网络检索，未经审核不入知识库、暂不加入行程。
 */
@Data
public class WebFoodCandidate {
    /** 店铺/小吃名 */
    private String name;
    /** 风味 */
    private String cuisine;
    /** 参考人均（元，网络信息估算） */
    private BigDecimal avgPrice;
    /** 位置（街道/商圈级别） */
    private String address;
    /** 为什么符合用户需求 */
    private String why;
}
