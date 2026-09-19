package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.PlaceKey;
import com.ghy.mutiagent.model.PlaceType;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.repository.entity.Attraction;
import com.ghy.mutiagent.repository.entity.Hotel;
import com.ghy.mutiagent.repository.entity.Restaurant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S05 契约测试（G02_01~03 同口径）：单日消费估算。
 * 住宿同日同酒店只计一晚；门票/餐费按人；公交按人数、出租按车辆数；往返大交通不计固定价。
 */
class BudgetCalculatorTest {

    private static PlanNode node(String type, long id) {
        PlanNode n = new PlanNode();
        n.setType(type);
        n.setPlaceId(id);
        return n;
    }

    private static Hotel hotel(long id, String price) {
        Hotel h = new Hotel();
        h.setId(id);
        h.setPricePerNight(new BigDecimal(price));
        return h;
    }

    private static Attraction attraction(long id, String price) {
        Attraction a = new Attraction();
        a.setId(id);
        a.setTicketPrice(new BigDecimal(price));
        return a;
    }

    private static Restaurant restaurant(long id, String price) {
        Restaurant r = new Restaurant();
        r.setId(id);
        r.setAvgPrice(new BigDecimal(price));
        return r;
    }

    @Test
    void 同日同酒店只住一晚() {
        assertThat(BudgetCalculator.dayCost(
                List.of(node("hotel", 1), node("hotel", 1)), 2,
                Map.of(), Map.of(), Map.of(1L, hotel(1, "300")), Map.of()))
                .isEqualByComparingTo("300");
    }

    @Test
    void 门票按人数() {
        assertThat(BudgetCalculator.dayCost(
                List.of(node("attraction", 2)), 2,
                Map.of(2L, attraction(2, "10")), Map.of(), Map.of(), Map.of()))
                .isEqualByComparingTo("20");
    }

    @Test
    void 餐费按人数() {
        assertThat(BudgetCalculator.dayCost(
                List.of(node("restaurant", 3)), 3,
                Map.of(), Map.of(3L, restaurant(3, "10")), Map.of(), Map.of()))
                .isEqualByComparingTo("30");
    }

    @Test
    void 公交按人数出租按车辆数() {
        Map<PlaceKey, double[]> coords = Map.of(
                PlaceKey.of(PlaceType.ATTRACTION, 2L), new double[]{120, 30},
                PlaceKey.of(PlaceType.ATTRACTION, 3L), new double[]{120, 30.05});
        // 5.5km → 公交 2元×3人 = 6
        assertThat(BudgetCalculator.dayCost(
                List.of(node("attraction", 2), node("attraction", 3)), 3,
                Map.of(), Map.of(), Map.of(), coords))
                .isEqualByComparingTo("6");
        // 远距离 → 出租按车辆数，与人数无关
        Map<PlaceKey, double[]> far = Map.of(
                PlaceKey.of(PlaceType.ATTRACTION, 2L), new double[]{120, 30},
                PlaceKey.of(PlaceType.ATTRACTION, 3L), new double[]{121, 31});
        double km = com.ghy.mutiagent.common.GeoUtils.distanceKm(120, 30, 121, 31);
        assertThat(BudgetCalculator.dayCost(
                List.of(node("attraction", 2), node("attraction", 3)), 5,
                Map.of(), Map.of(), Map.of(), far))
                .isEqualByComparingTo(BudgetCalculator.taxiCost(km).toPlainString());
    }

    @Test
    void 往返大交通不计固定价() {
        PlanNode transport = new PlanNode();
        transport.setType("transport");
        assertThat(BudgetCalculator.dayCost(List.of(transport), 2,
                Map.of(), Map.of(), Map.of(), Map.of()))
                .isZero();
    }
}
