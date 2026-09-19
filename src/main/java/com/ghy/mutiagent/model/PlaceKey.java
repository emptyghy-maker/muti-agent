package com.ghy.mutiagent.model;

/**
 * 跨类型复合地点键：类型 + 实体ID。坐标/评分/价格等统一索引一律以此为键，
 * 避免三张地点表数值 ID 相同（如景点1、餐厅1、酒店1）时互相覆盖。
 */
public record PlaceKey(PlaceType type, long id) {

    public static PlaceKey of(PlaceType type, long id) {
        return new PlaceKey(type, id);
    }
}
