package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.PlaceKey;
import com.ghy.mutiagent.model.PlaceType;
import com.ghy.mutiagent.repository.entity.Restaurant;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 坐标索引空值防护（回归）：联网检索入库的店铺没有坐标（lng/lat 为 null），
 * 建索引必须跳过而不是 NPE；排程侧对缺失坐标段已有固定时长兜底。
 */
class PlaceIndexNullCoordsTest {

    private static Restaurant food(long id, Double lng, Double lat) {
        Restaurant r = new Restaurant();
        r.setId(id);
        r.setName("店" + id);
        r.setLng(lng);
        r.setLat(lat);
        return r;
    }

    @Test
    void webRestaurantWithoutCoordsIsSkippedWithoutNpe() {
        Map<PlaceKey, double[]> coords = PlaceIndex.coords(
                List.of(), List.of(food(1L, 120.7, 31.3), food(2L, null, null)), List.of(), List.of());

        assertThat(coords).containsKey(PlaceKey.of(PlaceType.RESTAURANT, 1L));
        assertThat(coords).doesNotContainKey(PlaceKey.of(PlaceType.RESTAURANT, 2L));
        assertThat(coords).hasSize(1);
    }

    @Test
    void allNullCoordsYieldEmptyIndex() {
        Map<PlaceKey, double[]> coords = PlaceIndex.coords(
                List.of(), List.of(food(2L, null, null)), List.of(), List.of());

        assertThat(coords).isEmpty();
    }
}
