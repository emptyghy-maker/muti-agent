package com.ghy.mutiagent.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * POI 推荐事件（t_poi_recommend_event）：记录某 POI 被推荐/被勾选的独立会话事件。
 * 唯一键 (place_type, restaurant_id, event_type, session_id) 保证同一会话同一地点同一事件只计一次
 * （防重复计数/防刷），事件可回放，供晋升判定与离线对账（P2 实验 registry 口径）。
 */
@Data
@TableName("t_poi_recommend_event")
public class PoiRecommendEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long restaurantId;
    /** FOOD / ATTRACTION / HOTEL（历史数据默认 FOOD） */
    private String placeType;
    private Long destinationId;
    /** RECOMMEND=被纳入候选池展示 / SELECT=被用户勾选确认 */
    private String eventType;
    private String sessionId;
    private LocalDateTime createdAt;
}
