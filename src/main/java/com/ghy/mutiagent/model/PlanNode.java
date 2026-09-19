package com.ghy.mutiagent.model;

import lombok.Data;

/**
 * 行程节点：type = transport(启程/返程)/hotel/attraction/restaurant/rest(休息点)。
 * LLM 只输出 type + placeId + time + note，name 由 Java 按 placeId 回填（防编造）；
 * time 为 LLM 参考值，最终由 ScheduleBuilder 按通勤+停留时长重算（出发/通勤/停留一并写入）。
 */
@Data
public class PlanNode {
    private Integer seq;
    private String type;
    private Long placeId;
    private String name;
    /** HH:mm 到达时间（ScheduleBuilder 重算后的权威值） */
    private String time;
    private String note;
    /** 从上一地点出发的时间（当日首节点为空） */
    private String departTime;
    /** 上一地点到本点的通勤分钟（当日首节点为空） */
    private Integer travelMinutes;
    /** 本点停留分钟（ScheduleBuilder 重算） */
    private Integer durationMinutes;
    /** S09 稳定节点身份（如 d2-a2）：一次生成后不随排序/重编号变化；seq 只用于展示排序 */
    private String nodeId;
}
