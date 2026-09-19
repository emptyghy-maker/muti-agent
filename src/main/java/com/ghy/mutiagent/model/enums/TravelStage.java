package com.ghy.mutiagent.model.enums;

/**
 * 规划会话阶段（状态机）。
 *
 * INIT → PREFERENCE（问询）→ ATTRACTIONS → FOODS → HOTELS → ITINERARY → DONE；
 * CONFLICT 为偏好冲突二次确认；ADJUST 为行程修改模式（阶段 H）。
 */
public enum TravelStage {
    INIT,
    PREFERENCE,
    ATTRACTIONS,
    FOODS,
    HOTELS,
    ITINERARY,
    DONE,
    CONFLICT,
    ADJUST
}
