package com.ghy.mutiagent.model;

import lombok.Data;

import java.math.BigDecimal;

/** 联网检索的酒店候选（知识库外补充；经校验入库后进入备选池） */
@Data
public class WebHotelCandidate {
    private String name;
    /** 每晚价格（元） */
    private BigDecimal pricePerNight;
    /** 档次（经济/舒适/高档…） */
    private String level;
    /** 结构化标签（逗号分隔；入库硬门槛：必须提供，保证跨会话硬过滤可判定） */
    private String tags;
    /** 街道/商圈级位置 */
    private String address;
    /** 一句话理由（入库后写入 source_note） */
    private String why;
}
