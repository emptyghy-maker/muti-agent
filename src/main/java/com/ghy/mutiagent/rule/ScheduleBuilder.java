package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.common.GeoUtils;
import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.PlaceKey;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.repository.entity.Attraction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 行程时间轴确定性重算器（docs/agent-io-spec.md 行程时间规范）。
 *
 * LLM 只负责节点顺序，这里按「通勤时间 + 停留时长 + 饭点窗口锚定」前向推进，
 * 重算每个节点的出发/到达时间：到达时间写回 time，出发时间/通勤分钟/停留分钟写入节点新字段。
 * 时间单调递增，相邻节点不会重叠（游览时长与通勤都计入）。
 */
public final class ScheduleBuilder {

    /** 每天 09:00 开始 */
    private static final int DAY_START_MIN = 9 * 60;
    /** 交通节点到首站（出站/取行李），分钟 */
    private static final int STATION_TO_FIRST_MIN = 30;
    /** 餐厅停留，分钟 */
    private static final int MEAL_MIN = 90;
    /** 休息点停留，分钟 */
    private static final int REST_MIN = 60;
    /** 景点最短停留，分钟 */
    private static final int MIN_ATTRACTION_MIN = 60;
    /** 夜景时段起点（18:30），分钟 */
    private static final int NIGHT_START_MIN = 18 * 60 + 30;
    /** 夜景时段游览时长上限（2 小时），分钟 */
    private static final int NIGHT_VISIT_MAX_MIN = 120;

    private ScheduleBuilder() {
    }

    /**
     * 重算一天的时间轴。返回告警列表（如饭点超出窗口），由调用方打日志。
     * coords 为跨类型坐标索引（PlaceKey → [lng,lat]）；节点坐标必须先经 PlaceKeyResolver 解析。
     */
    public static List<String> schedule(DailyPlan day, Map<PlaceKey, double[]> coords,
                                        Map<Long, Attraction> attById) {
        return schedule(day, coords, attById, null);
    }

    /**
     * S10：facts 快照非空时，相邻节点时长取共享路线事实（排程/详情/计费同源，同一 factId）；
     * 为空时按 RouteFactEstimator 同口径确定性估算（行为与既有规则完全一致）。
     */
    public static List<String> schedule(DailyPlan day, Map<PlaceKey, double[]> coords,
                                        Map<Long, Attraction> attById,
                                        com.ghy.mutiagent.service.route.RouteFactSnapshot facts) {
        return schedule(day, coords, attById, facts, DAY_START_MIN);
    }

    /**
     * S10：facts 快照非空时，相邻节点时长取共享路线事实（排程/详情/计费同源，同一 factId）；
     * 为空时按 RouteFactEstimator 同口径确定性估算（行为与既有规则完全一致）。
     * startMin：当天首个节点时间（问卷起床时间，默认 09:00）。
     */
    public static List<String> schedule(DailyPlan day, Map<PlaceKey, double[]> coords,
                                        Map<Long, Attraction> attById,
                                        com.ghy.mutiagent.service.route.RouteFactSnapshot facts,
                                        int startMin) {
        List<String> warnings = new ArrayList<>();
        if (day == null || day.getNodes() == null || day.getNodes().isEmpty()) {
            return warnings;
        }
        int cursor = startMin;
        double[] prevCoord = null;
        PlaceKey prevKey = null;
        List<PlanNode> nodes = day.getNodes();
        for (int i = 0; i < nodes.size(); i++) {
            PlanNode n = nodes.get(i);
            double[] coord = PlaceKeyResolver.fromNode(n)
                    .map(k -> coords == null ? null : coords.get(k))
                    .orElse(null);
            PlaceKey key = PlaceKeyResolver.fromNode(n).orElse(null);
            if (i == 0) {
                // 首节点（抵达交通/起点酒店）：09:00 开始，无出发信息；停留时长同样推进 cursor
                int dur = durationOf(n, attById, cursor);
                n.setTime(toHm(cursor));
                n.setDepartTime(null);
                n.setTravelMinutes(null);
                n.setDurationMinutes(dur);
                cursor = cursor + dur;
            } else {
                int travel;
                if (facts != null && prevKey != null && key != null && prevCoord != null && coord != null) {
                    // S10：同一快照内的路线事实（含真实提供方时长）；无事实段回退确定性估算
                    com.ghy.mutiagent.model.RouteFact fact = facts.factFor(prevKey, key, prevCoord, coord);
                    travel = fact == null ? travelMinutes(prevCoord, coord) : fact.durationMin();
                } else {
                    travel = travelMinutes(prevCoord, coord);
                }
                int arrival = cursor + travel;
                String meal = mealWindowOf(n);
                if (meal != null) {
                    String winFrom = "午餐".equals(meal) ? MealTimeChecker.LUNCH_FROM : MealTimeChecker.DINNER_FROM;
                    String winTo = "午餐".equals(meal) ? MealTimeChecker.LUNCH_TO : MealTimeChecker.DINNER_TO;
                    int winStart = toMin(winFrom);
                    int winEnd = toMin(winTo);
                    if (arrival < winStart) {
                        // 到早了：晚点出发、准点开饭
                        arrival = winStart;
                    } else if (arrival > winEnd) {
                        warnings.add(meal + " 预计 " + toHm(arrival) + " 到达，超出窗口 "
                                + winFrom + "-" + winTo + "（" + n.getName() + "）");
                    }
                }
                int dur = durationOf(n, attById, arrival);
                n.setTravelMinutes(travel);
                n.setDepartTime(toHm(arrival - travel));
                n.setTime(toHm(arrival));
                n.setDurationMinutes(dur);
                cursor = arrival + dur;
            }
            prevCoord = coord;
            prevKey = key;
        }
        return warnings;
    }

    /** 节点停留分钟：景点按建议时长（最少 1 小时）、餐厅 90、休息点 60、交通/酒店 0。
     * 夜景时段（18:30 后到访的夜景标签景点）游览按 2 小时封顶（用户口径：晚餐后约 20:00-22:00 夜景）。
     * 开放时间封顶（确定性修复的根）：营业时间覆盖不了建议时长时压缩停留（不能排到闭馆之后）；
     * 压缩后不足最短停留则保持原时长——发布检查拦截，走修复循环换序/换点。 */
    private static int durationOf(PlanNode n, Map<Long, Attraction> attById, int arrivalMin) {
        return switch (n.getType() == null ? "" : n.getType()) {
            case "attraction" -> {
                Attraction a = attById == null ? null : attById.get(n.getPlaceId());
                double hours = a == null || a.getSuggestHours() == null ? 2.0 : a.getSuggestHours();
                int dur = Math.max(MIN_ATTRACTION_MIN, (int) Math.round(hours * 60));
                if (arrivalMin >= NIGHT_START_MIN && a != null && NightScorer.isNight(a.getTags())) {
                    dur = Math.min(dur, NIGHT_VISIT_MAX_MIN);
                }
                if (a != null) {
                    for (int[] it : OpeningHoursParser.parse(a.getOpenTime())) {
                        if (arrivalMin >= it[0] && arrivalMin < it[1]
                                && arrivalMin + dur > it[1]
                                && it[1] - arrivalMin >= MIN_ATTRACTION_MIN) {
                            dur = it[1] - arrivalMin;
                        }
                        break;
                    }
                }
                yield dur;
            }
            case "restaurant" -> MEAL_MIN;
            case "rest" -> REST_MIN;
            default -> 0;
        };
    }

    /** 相邻节点通勤分钟：S10 起统一走 RouteFactEstimator（单一估算口径），无坐标段固定 30 分钟 */
    private static int travelMinutes(double[] from, double[] to) {
        if (from == null || to == null) {
            return STATION_TO_FIRST_MIN;
        }
        double km = GeoUtils.distanceKm(from[0], from[1], to[0], to[1]);
        return com.ghy.mutiagent.service.route.RouteFactEstimator.durationMin(
                com.ghy.mutiagent.service.route.RouteFactEstimator.modeForKm(km), km);
    }

    /** note 里标注 午餐/晚餐 的餐厅节点按窗口锚定；未标注则按自然时间落位 */
    private static String mealWindowOf(PlanNode n) {
        if (n == null || !"restaurant".equals(n.getType()) || n.getNote() == null) {
            return null;
        }
        if (n.getNote().contains("晚餐")) {
            return "晚餐";
        }
        if (n.getNote().contains("午餐")) {
            return "午餐";
        }
        return null;
    }

    private static int toMin(String hm) {
        String[] p = hm.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    private static String toHm(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }
}
