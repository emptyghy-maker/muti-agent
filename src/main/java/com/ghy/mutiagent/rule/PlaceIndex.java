package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.AttractionCandidate;
import com.ghy.mutiagent.model.FoodCandidate;
import com.ghy.mutiagent.model.HotelCandidate;
import com.ghy.mutiagent.model.PlaceKey;
import com.ghy.mutiagent.model.PlaceType;
import com.ghy.mutiagent.repository.entity.Attraction;
import com.ghy.mutiagent.repository.entity.Hotel;
import com.ghy.mutiagent.repository.entity.Restaurant;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨类型地点索引构建（纯函数）：坐标与评分统一以 PlaceKey 为键，
 * 景点/餐厅/酒店即使数值 ID 相同也各落各的类型空间，互不覆盖。
 * 排程、账单、候选评分与行程生成的索引都由这里构建，保证一套规则。
 */
public final class PlaceIndex {

    private PlaceIndex() {
    }

    /** 坐标索引：景点（含休息点）/餐厅/酒店 → [lng, lat] */
    public static Map<PlaceKey, double[]> coords(List<Attraction> attractions,
                                                 List<Restaurant> restaurants,
                                                 List<Hotel> hotels,
                                                 List<Attraction> restSpots) {
        Map<PlaceKey, double[]> map = new HashMap<>();
        attractions.forEach(a -> map.put(PlaceKey.of(PlaceType.ATTRACTION, a.getId()),
                new double[]{a.getLng(), a.getLat()}));
        if (restSpots != null) {
            restSpots.forEach(a -> map.put(PlaceKey.of(PlaceType.ATTRACTION, a.getId()),
                    new double[]{a.getLng(), a.getLat()}));
        }
        restaurants.forEach(r -> map.put(PlaceKey.of(PlaceType.RESTAURANT, r.getId()),
                new double[]{r.getLng(), r.getLat()}));
        hotels.forEach(h -> map.put(PlaceKey.of(PlaceType.HOTEL, h.getId()),
                new double[]{h.getLng(), h.getLat()}));
        return map;
    }

    /** 候选池综合评分索引：按候选实体的类型落键 */
    public static Map<PlaceKey, Double> scores(List<AttractionCandidate> attractions,
                                               List<FoodCandidate> foods,
                                               List<HotelCandidate> hotels) {
        Map<PlaceKey, Double> map = new HashMap<>();
        if (attractions != null) {
            attractions.forEach(c -> map.put(PlaceKey.of(PlaceType.ATTRACTION, c.getAttractionId()),
                    nvl(c.getScore())));
        }
        if (foods != null) {
            foods.forEach(g -> g.getRestaurants().forEach(it ->
                    map.put(PlaceKey.of(PlaceType.RESTAURANT, it.getRestaurantId()), nvl(it.getScore()))));
        }
        if (hotels != null) {
            hotels.forEach(c -> map.put(PlaceKey.of(PlaceType.HOTEL, c.getHotelId()), nvl(c.getScore())));
        }
        return map;
    }

    private static double nvl(Double v) {
        return v == null ? 0.0 : v;
    }
}
