package com.ghy.mutiagent.model;

/**
 * S10 路线查询（统一内部接口的唯一输入）：类型化起终点、日期、出发方式、
 * 约束修订号（迟到旧版本结果丢弃）与坐标版本（地点坐标变化即换键）。
 * fromCoord/toCoord 供估算降级计算距离，可为空（真实提供方自行解析坐标）。
 */
public record RouteQuery(PlaceKey from, PlaceKey to, String date, String mode,
                         double[] fromCoord, double[] toCoord,
                         long coordVersion, int constraintRevision) {
}
