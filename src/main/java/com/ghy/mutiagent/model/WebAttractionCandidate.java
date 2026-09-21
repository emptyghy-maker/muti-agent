package com.ghy.mutiagent.model;

import lombok.Data;

import java.math.BigDecimal;

/** 联网检索的景点候选（知识库外补充；经校验入库后进入备选池） */
@Data
public class WebAttractionCandidate {
    private String name;
    /** 景点类型（自然风光/文化历史/打卡拍照…） */
    private String category;
    /** 结构化标签（逗号分隔；入库硬门槛：必须提供，保证跨会话硬过滤可判定） */
    private String tags;
    /** 门票（元）；免费写 0，不确定为 null 并在 why 说明 */
    private BigDecimal ticketPrice;
    /** 街道/商圈级位置 */
    private String address;
    /** 一句话理由（入库后写入 source_note） */
    private String why;
}
