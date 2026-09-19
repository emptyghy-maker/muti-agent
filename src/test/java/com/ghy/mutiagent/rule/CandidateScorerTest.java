package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.repository.entity.Hotel;
import com.ghy.mutiagent.repository.entity.Restaurant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 候选评分器单测：用固定坐标/价格构造小候选池，验证
 * ① 默认权重下路径优 > 成本低 > 需求匹配的排序效果；
 * ② 美食需求突出时口味匹配的店铺反超；
 * ③ 性价比偏好下经济/舒适档酒店得分高于豪华。
 */
class CandidateScorerTest {

    private TravelPreference pref() {
        TravelPreference p = new TravelPreference();
        p.setTotalBudget(new BigDecimal("3000"));
        p.setDays(3);
        p.setPeopleCount(2);
        p.setAttractionType("打卡拍照");
        p.setFoodTaste("辣");
        p.setHotelStyle("性价比优先");
        return p;
    }

    private Restaurant r(long id, String cuisine, double price, double lng, double lat, double rating) {
        Restaurant r = new Restaurant();
        r.setId(id);
        r.setCuisine(cuisine);
        r.setAvgPrice(BigDecimal.valueOf(price));
        r.setLng(lng);
        r.setLat(lat);
        r.setRating(rating);
        return r;
    }

    private Hotel h(long id, double price, String level, double lng, double lat, double rating) {
        Hotel h = new Hotel();
        h.setId(id);
        h.setPricePerNight(BigDecimal.valueOf(price));
        h.setLevel(level);
        h.setLng(lng);
        h.setLat(lat);
        h.setRating(rating);
        return h;
    }

    @Test
    void 默认权重下距离近者优先于便宜且口味匹配但偏远者() {
        double[] center = {118.8, 32.02};
        List<Restaurant> pool = List.of(
                r(1L, "本地菜", 200, 118.80, 32.02, 4.9),   // 紧邻中心、贵、口味不匹配
                r(2L, "火锅", 80, 119.10, 32.40, 4.6),      // 偏远、便宜、口味匹配
                r(3L, "杭帮菜", 250, 119.20, 32.50, 4.5));  // 更远、更贵、口味不匹配
        Map<Long, Double> s = CandidateScorer.scoreRestaurants(pool, center, pref(),
                AhpWeightCalculator.defaultWeights());
        // 路径优权重最大：近店 > 远店；同类远店中便宜者更优
        assertThat(s.get(1L)).isGreaterThan(s.get(2L));
        assertThat(s.get(2L)).isGreaterThan(s.get(3L));
    }

    @Test
    void 美食权重提高后口味匹配的店反超更近的店() {
        double[] center = {118.8, 32.02};
        List<Restaurant> pool = List.of(
                r(1L, "本地菜", 200, 118.80, 32.02, 4.9),   // 距离略优、贵、口味不匹配
                r(2L, "火锅", 80, 118.81, 32.03, 4.6),      // 稍远、便宜、口味匹配
                r(3L, "杭帮菜", 250, 119.20, 32.50, 4.5));  // 远且贵
        Map<String, Double> w = AhpWeightCalculator.adjust(
                Map.of("path", 2, "cost", 3, "sightseeing", 2, "food", 5));
        Map<Long, Double> s = CandidateScorer.scoreRestaurants(pool, center, pref(), w);
        double max = s.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        assertThat(s.get(2L)).isEqualTo(max);
    }

    @Test
    void 性价比偏好下经济舒适档酒店得分高于豪华() {
        double[] center = {118.8, 32.02};
        List<Hotel> pool = List.of(
                h(1L, 780, "豪华", 118.78, 32.04, 4.8),
                h(2L, 350, "舒适", 118.81, 32.03, 4.6),
                h(3L, 260, "经济", 118.85, 32.05, 4.4));
        Map<Long, Double> s = CandidateScorer.scoreHotels(pool, center, pref(),
                AhpWeightCalculator.defaultWeights());
        assertThat(s.get(3L)).isGreaterThan(s.get(1L));
        assertThat(s.get(2L)).isGreaterThan(s.get(1L));
    }
}
