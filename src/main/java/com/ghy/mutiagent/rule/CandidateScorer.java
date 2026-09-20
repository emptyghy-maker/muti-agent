package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.common.GeoUtils;
import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.repository.entity.Attraction;
import com.ghy.mutiagent.repository.entity.Hotel;
import com.ghy.mutiagent.repository.entity.Restaurant;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 候选综合评分器：AHP 权重 × 准则量化分（0~1）→ 总分（0~10，1 位小数）。
 *
 * 各候选类型的适用准则（权重按适用子集重归一化）：
 * - 景点：路径优（聚集度）+ 成本低（门票）+ 旅游需求（类型匹配 + 评分）；
 * - 餐厅：路径优（距已选景点中心）+ 成本低（人均）+ 美食需求（口味匹配 + 评分）；
 * - 酒店：路径优（距已选点中心）+ 成本低（房价）+ 旅游需求（档次匹配 + 评分）。
 */
public final class CandidateScorer {

    private static final Map<String, Double> DEFAULT_WEIGHTS = AhpWeightCalculator.defaultWeights();

    private CandidateScorer() {
    }

    // ==================== 景点 ====================

    public static Map<Long, Double> scoreAttractions(List<Attraction> pool, TravelPreference p,
                                                     Map<String, Double> w) {
        double min = pool.stream().mapToDouble(a -> num(a.getTicketPrice())).min().orElse(0);
        double max = pool.stream().mapToDouble(a -> num(a.getTicketPrice())).max().orElse(0);
        double budget = perPersonDaily(p);
        Map<Long, Double> out = new LinkedHashMap<>();
        for (Attraction a : pool) {
            double path = 1 / (1 + avgDistKm(a, pool));
            double cost = costScore(num(a.getTicketPrice()), min, max, budget);
            double sight = 0.5 * ratingOf(a.getRating())
                    + 0.5 * attractionTypeMatch(a.getCategory(), p == null ? null : p.getAttractionType());
            out.put(a.getId(), sum(w, path, cost, sight, AhpWeightCalculator.SIGHTSEEING));
        }
        return out;
    }

    // ==================== 餐厅 ====================

    /** centroid 为已选景点的几何中心（未选景点时为 null，路径分取中性 0.5） */
    public static Map<Long, Double> scoreRestaurants(List<Restaurant> pool, double[] centroid,
                                                     TravelPreference p, Map<String, Double> w) {
        double min = pool.stream().mapToDouble(r -> num(r.getAvgPrice())).min().orElse(0);
        double max = pool.stream().mapToDouble(r -> num(r.getAvgPrice())).max().orElse(0);
        double budget = perPersonDaily(p);
        Map<Long, Double> out = new LinkedHashMap<>();
        for (Restaurant r : pool) {
            // 网搜扩充店无坐标：路径分取中性 0.5（不参与距离排序）
            double path = centroid == null || r.getLng() == null || r.getLat() == null
                    ? 0.5
                    : 1 / (1 + GeoUtils.distanceKm(centroid[0], centroid[1], r.getLng(), r.getLat()));
            double cost = costScore(num(r.getAvgPrice()), min, max, budget);
            double food = 0.5 * ratingOf(r.getRating())
                    + 0.5 * tasteMatch(r.getCuisine(), p == null ? null : p.getFoodTaste());
            out.put(r.getId(), sum(w, path, cost, food, AhpWeightCalculator.FOOD));
        }
        return out;
    }

    // ==================== 酒店 ====================

    /** centroid 为已选景点+餐厅的几何中心（为空时路径分取中性 0.5） */
    public static Map<Long, Double> scoreHotels(List<Hotel> pool, double[] centroid,
                                                TravelPreference p, Map<String, Double> w) {
        double min = pool.stream().mapToDouble(h -> num(h.getPricePerNight())).min().orElse(0);
        double max = pool.stream().mapToDouble(h -> num(h.getPricePerNight())).max().orElse(0);
        double budget = perPersonDaily(p);
        Map<Long, Double> out = new LinkedHashMap<>();
        for (Hotel h : pool) {
            double path = centroid == null ? 0.5
                    : 1 / (1 + GeoUtils.distanceKm(centroid[0], centroid[1], h.getLng(), h.getLat()));
            double cost = costScore(num(h.getPricePerNight()), min, max, budget);
            double sight = 0.5 * ratingOf(h.getRating())
                    + 0.5 * levelMatch(h.getLevel(), p == null ? null : p.getHotelStyle());
            out.put(h.getId(), sum(w, path, cost, sight, AhpWeightCalculator.SIGHTSEEING));
        }
        return out;
    }

    // ==================== 内部工具 ====================

    /** 适用准则子集重归一化加权 → 0~10（1 位小数） */
    private static double sum(Map<String, Double> w, double vPath, double vCost, double vThird, String thirdKey) {
        double wp = wv(w, AhpWeightCalculator.PATH);
        double wc = wv(w, AhpWeightCalculator.COST);
        double wt = wv(w, thirdKey);
        double total = (wp * vPath + wc * vCost + wt * vThird) / (wp + wc + wt);
        return Math.round(total * 100) / 10.0;
    }

    private static double wv(Map<String, Double> w, String key) {
        Double v = w == null ? null : w.get(key);
        return v == null ? DEFAULT_WEIGHTS.get(key) : v;
    }

    /** 池内相对价格分（0~1，越便宜越高）；单价超人均日预算时 ×0.6 惩罚 */
    private static double costScore(double price, double min, double max, double budget) {
        double rel = max <= min ? 0.5 : 1 - (price - min) / (max - min);
        if (budget > 0 && price > budget) {
            rel *= 0.6;
        }
        return Math.max(0, Math.min(1, rel));
    }

    private static double perPersonDaily(TravelPreference p) {
        if (p == null) {
            return 0;
        }
        double budget = p.getTotalBudget() == null ? 3000 : p.getTotalBudget().doubleValue();
        double days = Math.max(1, p.getDays() == null ? 3 : p.getDays());
        double people = Math.max(1, p.getPeopleCount() == null ? 2 : p.getPeopleCount());
        return budget / days / people;
    }

    private static double avgDistKm(Attraction a, List<Attraction> pool) {
        if (pool == null || pool.size() <= 1) {
            return 0;
        }
        double sumKm = 0;
        int n = 0;
        for (Attraction o : pool) {
            if (o.getId() != null && o.getId().equals(a.getId())) {
                continue;
            }
            sumKm += GeoUtils.distanceKm(a.getLng(), a.getLat(), o.getLng(), o.getLat());
            n++;
        }
        return n == 0 ? 0 : sumKm / n;
    }

    private static double ratingOf(Double r) {
        return r == null ? 0.5 : Math.max(0, Math.min(1, r / 5));
    }

    private static double attractionTypeMatch(String category, String pref) {
        if (pref == null || "混合".equals(pref)) {
            return 0.7;
        }
        if ("打卡拍照".equals(pref)) {
            return ("打卡拍照".equals(category) || "文化历史".equals(category)) ? 1 : 0.4;
        }
        if ("娱乐项目".equals(pref)) {
            return "娱乐项目".equals(category) ? 1 : 0.4;
        }
        return 0.7;
    }

    private static double tasteMatch(String cuisine, String pref) {
        if (pref == null) {
            return 0.7;
        }
        List<String> preferred = switch (pref) {
            case "辣" -> List.of("火锅", "川菜");
            case "清淡" -> List.of("素斋", "小吃", "本地菜");
            case "本地特色菜" -> List.of("本地菜", "小吃");
            default -> List.of();
        };
        if (preferred.isEmpty()) {
            return 0.7;
        }
        return preferred.contains(cuisine) ? 1 : 0.4;
    }

    private static double levelMatch(String level, String style) {
        if (style == null || level == null) {
            return 0.7;
        }
        return switch (style) {
            case "性价比优先" -> switch (level) {
                case "经济", "舒适" -> 1;
                case "高档" -> 0.5;
                default -> 0.3;
            };
            case "体验优先" -> switch (level) {
                case "高档", "豪华" -> 1;
                case "舒适" -> 0.6;
                default -> 0.4;
            };
            default -> 0.7;
        };
    }

    private static double num(BigDecimal v) {
        return v == null ? 0 : v.doubleValue();
    }
}
