package com.ghy.mutiagent.model;

/**
 * 地点实体类型：与数据库三张地点表（景点/餐厅/酒店）一一对应。
 * 三张表各自独立自增，数值 ID 跨表会重复，必须先确定类型再解释 ID。
 */
public enum PlaceType {
    ATTRACTION, RESTAURANT, HOTEL
}
