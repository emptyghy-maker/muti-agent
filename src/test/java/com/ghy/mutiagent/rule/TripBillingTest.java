package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.BudgetBreakdown;
import com.ghy.mutiagent.model.CostLine;
import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.PlaceKey;
import com.ghy.mutiagent.model.PlaceType;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.model.StayBooking;
import com.ghy.mutiagent.repository.entity.Hotel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S05/A07 契约测试：全程账单（住宿按夜计费、按人计价、未知大交通、细项合计=总额）。
 */
class TripBillingTest {

    private static PlanNode node(String type, Long placeId, String note) {
        PlanNode n = new PlanNode();
        n.setType(type);
        n.setPlaceId(placeId);
        n.setName(type + placeId);
        n.setNote(note);
        return n;
    }

    private static DailyPlan day(int index, PlanNode... nodes) {
        DailyPlan d = new DailyPlan();
        d.setDayIndex(index);
        d.setNodes(new ArrayList<>(List.of(nodes)));
        return d;
    }

    private static ItineraryPlan plan(DailyPlan... days) {
        ItineraryPlan p = new ItineraryPlan();
        p.setDays(new ArrayList<>(List.of(days)));
        return p;
    }

    private static Hotel hotel(long id, String price) {
        Hotel h = new Hotel();
        h.setId(id);
        h.setPricePerNight(new BigDecimal(price));
        return h;
    }

    private static BigDecimal lodgingOf(BudgetBreakdown b) {
        return b.getLines().stream()
                .filter(l -> "住宿".equals(l.category()))
                .map(CostLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void 两天一晚房费三百只算三百() {
        List<StayBooking> stays = List.of(new StayBooking(1, 1L, 1, new BigDecimal("300")));
        BudgetBreakdown b = TripBilling.calculateTrip(
                plan(day(1, node("hotel", 1L, null)), day(2, node("hotel", 1L, null))),
                stays, 2, new PriceSnapshot(Map.of()), Map.of(), null);
        assertThat(lodgingOf(b)).isEqualByComparingTo("300");
        assertThat(b.getKnownSubtotal()).isEqualByComparingTo("300");
    }

    @Test
    void 三天两晚两间房共一千二且离店日零住宿() {
        List<StayBooking> stays = List.of(
                new StayBooking(1, 1L, 2, new BigDecimal("300")),
                new StayBooking(2, 1L, 2, new BigDecimal("300")));
        BudgetBreakdown b = TripBilling.calculateTrip(
                plan(day(1, node("hotel", 1L, null)), day(2, node("hotel", 1L, null)),
                        day(3, node("hotel", 1L, null))),
                stays, 2, new PriceSnapshot(Map.of()), Map.of(), null);
        assertThat(lodgingOf(b)).isEqualByComparingTo("1200");
        // 离店日（第3天）不产生夜次：晚数止于 2
        assertThat(stays).allSatisfy(s -> assertThat(s.nightIndex()).isLessThan(3));
    }

    @Test
    void 一天游零住宿() {
        assertThat(TripBilling.defaultStays(1, hotel(1L, "300"), 2)).isEmpty();
        BudgetBreakdown b = TripBilling.calculateTrip(
                plan(day(1, node("hotel", 1L, null))),
                List.of(), 2, new PriceSnapshot(Map.of()), Map.of(), null);
        assertThat(lodgingOf(b)).isZero();
    }

    @Test
    void 门票餐费按人计价() {
        Map<PlaceKey, BigDecimal> prices = Map.of(
                PlaceKey.of(PlaceType.ATTRACTION, 2L), new BigDecimal("10"),
                PlaceKey.of(PlaceType.RESTAURANT, 3L), new BigDecimal("20"));
        BudgetBreakdown b = TripBilling.calculateTrip(
                plan(day(1, node("attraction", 2L, null), node("restaurant", 3L, null))),
                List.of(), 3, new PriceSnapshot(prices), Map.of(), null);
        assertThat(b.getLines()).anySatisfy(l -> {
            assertThat(l.category()).isEqualTo("门票");
            assertThat(l.amount()).isEqualByComparingTo("30");
        });
        assertThat(b.getLines()).anySatisfy(l -> {
            assertThat(l.category()).isEqualTo("餐费");
            assertThat(l.amount()).isEqualByComparingTo("60");
        });
    }

    @Test
    void 未知大交通不纳入已确认总费用() {
        BudgetBreakdown b = TripBilling.calculateTrip(
                plan(day(1, node("transport", null, "抵达"), node("attraction", 2L, null))),
                List.of(), 2,
                new PriceSnapshot(Map.of(PlaceKey.of(PlaceType.ATTRACTION, 2L), new BigDecimal("10"))),
                Map.of(), new BigDecimal("5000"));
        assertThat(b.getUnknownCategories()).contains("大交通");
        assertThat(b.getBudgetCoverage()).isEqualTo(TripBilling.COVERAGE_UNKNOWN);
        // 已确认小计只含门票 20，不含任何固定交通价
        assertThat(b.getKnownSubtotal()).isEqualByComparingTo("20");
    }

    @Test
    void 公交按人数出租按车辆数() {
        // A(120,30) → B(120.001,30) ≈ 0.11km 步行；B → C(120,30.05) ≈ 5.5km 公交
        Map<PlaceKey, BigDecimal> prices = Map.of(
                PlaceKey.of(PlaceType.ATTRACTION, 2L), BigDecimal.ZERO,
                PlaceKey.of(PlaceType.ATTRACTION, 3L), BigDecimal.ZERO);
        Map<PlaceKey, double[]> coords = Map.of(
                PlaceKey.of(PlaceType.ATTRACTION, 2L), new double[]{120, 30},
                PlaceKey.of(PlaceType.ATTRACTION, 3L), new double[]{120, 30.05});
        BudgetBreakdown b = TripBilling.calculateTrip(
                plan(day(1, node("attraction", 2L, null), node("attraction", 3L, null))),
                List.of(), 3, new PriceSnapshot(prices), coords, null);
        assertThat(b.getLines()).anySatisfy(l -> {
            assertThat(l.category()).isEqualTo("交通");
            assertThat(l.reference()).isEqualTo("公交");
            assertThat(l.quantity()).isEqualByComparingTo("3"); // 公交按人数
            assertThat(l.amount()).isEqualByComparingTo("6");
        });
        // 出租车按车辆数：数量恒为 1
        Map<PlaceKey, double[]> farCoords = Map.of(
                PlaceKey.of(PlaceType.ATTRACTION, 2L), new double[]{120, 30},
                PlaceKey.of(PlaceType.ATTRACTION, 3L), new double[]{121, 31});
        BudgetBreakdown far = TripBilling.calculateTrip(
                plan(day(1, node("attraction", 2L, null), node("attraction", 3L, null))),
                List.of(), 3, new PriceSnapshot(prices), farCoords, null);
        assertThat(far.getLines()).anySatisfy(l -> {
            assertThat(l.reference()).isEqualTo("出租车");
            assertThat(l.quantity()).isEqualByComparingTo("1");
        });
    }

    @Test
    void 细项合计等于总额() {
        List<StayBooking> stays = List.of(new StayBooking(1, 1L, 1, new BigDecimal("300")));
        Map<PlaceKey, BigDecimal> prices = Map.of(
                PlaceKey.of(PlaceType.ATTRACTION, 2L), new BigDecimal("10"),
                PlaceKey.of(PlaceType.RESTAURANT, 3L), new BigDecimal("20"));
        BudgetBreakdown b = TripBilling.calculateTrip(
                plan(day(1, node("hotel", 1L, null), node("attraction", 2L, null),
                        node("restaurant", 3L, null))),
                stays, 2, new PriceSnapshot(prices), Map.of(), new BigDecimal("8000"));
        BigDecimal sum = b.getLines().stream().map(CostLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(b.getTotalAmount());
        assertThat(b.getKnownSubtotal()).isEqualByComparingTo("360");
        assertThat(b.isOverLimit()).isFalse();
    }

    @Test
    void 已核实费用超预算标记超限() {
        Map<PlaceKey, BigDecimal> prices = Map.of(
                PlaceKey.of(PlaceType.ATTRACTION, 2L), new BigDecimal("10"));
        BudgetBreakdown b = TripBilling.calculateTrip(
                plan(day(1, node("attraction", 2L, null))),
                List.of(), 2, new PriceSnapshot(prices), Map.of(), new BigDecimal("10"));
        assertThat(b.isOverLimit()).isTrue();
    }

    @Test
    void 缺价格不默认免费() {
        BudgetBreakdown b = TripBilling.calculateTrip(
                plan(day(1, node("attraction", 2L, null))),
                List.of(), 2, new PriceSnapshot(Map.of()), Map.of(), null);
        assertThat(b.getUnknownCategories()).contains("门票");
        assertThat(b.getLines()).noneMatch(l -> "门票".equals(l.category()));
        assertThat(b.getBudgetCoverage()).isEqualTo(TripBilling.COVERAGE_UNKNOWN);
    }
}
