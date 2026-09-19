package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.common.GeoUtils;
import com.ghy.mutiagent.model.BudgetBreakdown;
import com.ghy.mutiagent.model.CostLine;
import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.PlaceKey;
import com.ghy.mutiagent.model.PlaceType;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.model.StayBooking;
import com.ghy.mutiagent.repository.entity.Hotel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 全程消费账单计算（S05）：全程汇总是预算检查的唯一入口，按天分配仅用于展示。
 * - 门票/餐费按人计价；公交按人数、出租车按车辆数；
 * - 往返大交通（机场/高铁）金额未知 → 记 unknownCategories，不把固定 60 元当真实票价；
 * - 住宿只来自夜次 stays（第 1..days-1 晚）：离店日从酒店出发不增加夜次，酒店节点进出多次不计费；
 * - 重复游玩同一景点不悄悄去重门票：按节点出现次数计费，是否重复收费由票务假设决定；
 * - 缺价格记 unknown，不默认免费；负数价格视为未核实。
 */
public final class TripBilling {

    public static final String COVERAGE_FULLY_VERIFIED = "FULLY_VERIFIED";
    public static final String COVERAGE_UNKNOWN = "UNKNOWN";

    /** 起步价 + 每公里（出租车按车辆数计） */
    private static final BigDecimal BUS_FARE = BigDecimal.valueOf(2);

    private TripBilling() {
    }

    /** 默认房间数：2 人及以下 1 间，之后每 2 人加 1 间 */
    public static int defaultRooms(int people) {
        return Math.max(1, (people + 1) / 2);
    }

    /** days 天行程默认生成 days-1 晚住宿；酒店为空或 1 天游 → 无夜次 */
    public static List<StayBooking> defaultStays(int days, Hotel hotel, int rooms) {
        if (days <= 1 || hotel == null) {
            return List.of();
        }
        List<StayBooking> stays = new ArrayList<>();
        for (int night = 1; night <= days - 1; night++) {
            stays.add(new StayBooking(night, hotel.getId(), Math.max(1, rooms),
                    hotel.getPricePerNight()));
        }
        return stays;
    }

    /**
     * 计算全程账单。
     * @param coords 跨类型坐标索引（相邻节点交通估算用，可为空：无坐标段不计交通）
     * @param totalBudget 预算硬上限，可为 null（null 时不判超支）
     */
    public static BudgetBreakdown calculateTrip(ItineraryPlan plan, List<StayBooking> stays,
                                                int party, PriceSnapshot prices,
                                                Map<PlaceKey, double[]> coords,
                                                BigDecimal totalBudget) {
        return calculateTrip(plan, stays, party, prices, coords, totalBudget, null);
    }

    /**
     * S10：facts 快照非空时交通费用取自共享路线事实（与排程/详情同源同一 factId）；
     * 为空时按既有确定性规则估算（口径与 RouteFactEstimator 一致）。
     */
    public static BudgetBreakdown calculateTrip(ItineraryPlan plan, List<StayBooking> stays,
                                                int party, PriceSnapshot prices,
                                                Map<PlaceKey, double[]> coords,
                                                BigDecimal totalBudget,
                                                com.ghy.mutiagent.service.route.RouteFactSnapshot facts) {
        List<CostLine> lines = new ArrayList<>();
        Set<String> unknown = new LinkedHashSet<>();
        if (plan != null && plan.getDays() != null) {
            for (DailyPlan d : plan.getDays()) {
                List<PlanNode> nodes = d.getNodes() == null ? List.of() : d.getNodes();
                for (PlanNode n : nodes) {
                    lineForNode(n, party, prices, unknown, lines);
                }
                addTransitLines(nodes, party, coords, facts, lines);
            }
        }
        for (StayBooking s : stays == null ? List.<StayBooking>of() : stays) {
            if (s.nightlyRate() == null || s.nightlyRate().signum() < 0) {
                unknown.add("住宿");
            } else {
                BigDecimal amount = s.nightlyRate().multiply(BigDecimal.valueOf(s.rooms()));
                lines.add(new CostLine("住宿", "酒店#" + s.hotelId() + " 第" + s.nightIndex() + "晚",
                        s.nightlyRate(), BigDecimal.valueOf(s.rooms()), amount, false));
            }
        }

        BigDecimal known = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        boolean anyEstimated = false;
        for (CostLine l : lines) {
            total = total.add(l.amount());
            if (l.estimated()) {
                anyEstimated = true;
            } else {
                known = known.add(l.amount());
            }
        }
        BudgetBreakdown b = new BudgetBreakdown();
        b.setLines(lines);
        b.setKnownSubtotal(known);
        b.setTotalAmount(total);
        b.setUnknownCategories(new ArrayList<>(unknown));
        b.setBudgetCoverage(unknown.isEmpty() && !anyEstimated ? COVERAGE_FULLY_VERIFIED : COVERAGE_UNKNOWN);
        b.setTotalBudget(totalBudget);
        b.setOverLimit(totalBudget != null && known.compareTo(totalBudget) > 0);
        return b;
    }

    /** 单节点费用：门票/餐费按人计价；酒店节点不直接计费（住宿来自夜次）；交通节点金额未知 */
    private static void lineForNode(PlanNode n, int party, PriceSnapshot prices,
                                    Set<String> unknown, List<CostLine> lines) {
        Optional<PlaceKey> key = PlaceKeyResolver.fromNode(n);
        if (key.isEmpty()) {
            if ("transport".equals(n.getType())) {
                unknown.add("大交通");
            }
            return;
        }
        BigDecimal price = prices.priceOf(key.get());
        if (price == null || price.signum() < 0) {
            unknown.add(categoryOf(key.get().type()));
            return;
        }
        BigDecimal quantity = BigDecimal.valueOf(Math.max(1, party));
        switch (key.get().type()) {
            case ATTRACTION -> lines.add(new CostLine("门票", n.getName(), price, quantity,
                    price.multiply(quantity), false));
            case RESTAURANT -> lines.add(new CostLine("餐费", n.getName(), price, quantity,
                    price.multiply(quantity), false));
            case HOTEL -> { }
        }
    }

    /** 相邻实体节点间交通：S10 起有事实快照时取事实费用；否则 ≤1km 步行 0 元、1~8km 公交按人数、>8km 出租车 */
    private static void addTransitLines(List<PlanNode> nodes, int party,
                                        Map<PlaceKey, double[]> coords,
                                        com.ghy.mutiagent.service.route.RouteFactSnapshot facts,
                                        List<CostLine> lines) {
        for (int i = 1; i < nodes.size(); i++) {
            Optional<PlaceKey> a = PlaceKeyResolver.fromNode(nodes.get(i - 1));
            Optional<PlaceKey> b = PlaceKeyResolver.fromNode(nodes.get(i));
            if (a.isEmpty() || b.isEmpty()) {
                continue;
            }
            double[] ca = coords == null ? null : coords.get(a.get());
            double[] cb = coords == null ? null : coords.get(b.get());
            if (ca == null || cb == null) {
                continue;
            }
            double km = GeoUtils.distanceKm(ca[0], ca[1], cb[0], cb[1]);
            if (facts != null) {
                // S10：共享路线事实（真实提供方费用或明确标注的估算；估算按同口径×实际人数）
                com.ghy.mutiagent.model.RouteFact fact = facts.factFor(a.get(), b.get(), ca, cb);
                if (fact != null) {
                    BigDecimal amount = com.ghy.mutiagent.model.RouteFact.KIND_ESTIMATE.equals(fact.kind())
                            ? com.ghy.mutiagent.service.route.RouteFactEstimator.cost(
                                    fact.mode(), km, Math.max(1, party))
                            : fact.cost();
                    if (amount != null && amount.signum() > 0) {
                        lines.add(new CostLine("交通", transportLabel(fact.mode()), amount,
                                BigDecimal.ONE, amount,
                                com.ghy.mutiagent.model.RouteFact.KIND_ESTIMATE.equals(fact.kind())));
                    }
                }
                continue;
            }
            if (km <= 1) {
                continue;
            }
            if (km <= 8) {
                BigDecimal amount = BUS_FARE.multiply(BigDecimal.valueOf(Math.max(1, party)));
                lines.add(new CostLine("交通", "公交", BUS_FARE,
                        BigDecimal.valueOf(Math.max(1, party)), amount, true));
            } else {
                BigDecimal amount = BudgetCalculator.taxiCost(km);
                lines.add(new CostLine("交通", "出租车", amount, BigDecimal.ONE, amount, true));
            }
        }
    }

    private static String transportLabel(String mode) {
        return switch (mode == null ? "" : mode) {
            case "WALK" -> "步行";
            case "TRANSIT" -> "公交";
            case "TAXI" -> "出租车";
            default -> "交通";
        };
    }

    private static String categoryOf(PlaceType t) {
        return switch (t) {
            case ATTRACTION -> "门票";
            case RESTAURANT -> "餐费";
            case HOTEL -> "住宿";
        };
    }
}
