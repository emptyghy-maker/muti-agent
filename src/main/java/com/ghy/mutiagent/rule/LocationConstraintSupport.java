package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.common.GeoUtils;
import com.ghy.mutiagent.model.LocationConstraint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** “某地附近”需求的确定性解析、定位与距离判定。 */
public final class LocationConstraintSupport {

    public static final String ATTRACTION = "ATTRACTION";
    public static final String FOOD = "FOOD";
    public static final String HOTEL = "HOTEL";
    public static final double DEFAULT_RADIUS_KM = 3.0;

    private static final Pattern NEAR_PATTERN = Pattern.compile(
            "([^，。；;！？!?\\n]{1,24}?)(?:附近|周边|一带)");
    private static final Pattern RADIUS_PATTERN = Pattern.compile(
            "(?:半径|范围|方圆|不超过|最多)?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:公里|千米|km)",
            Pattern.CASE_INSENSITIVE);
    private static final List<String> CLEAR_WORDS = List.of(
            "不限地点", "位置不限", "不限制地点", "不限制位置", "哪里都可以", "不要求附近");

    /** 内置演示城市常用商圈；未知锚点保留为 UNRESOLVED，绝不伪造坐标。 */
    private static final Map<String, Map<String, double[]>> ANCHORS = anchors();

    private LocationConstraintSupport() {
    }

    public static LocationConstraint resolve(String destination, String text, List<String> defaultScopes) {
        if (text == null || text.isBlank() || clearRequested(text)) {
            return null;
        }
        Matcher matcher = NEAR_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String anchor = canonicalAnchor(destination, normalizeAnchor(matcher.group(1)));
        if (anchor.isBlank()) {
            return null;
        }
        LocationConstraint out = new LocationConstraint();
        out.setAnchorName(anchor);
        out.setRadiusKm(radiusOf(text));
        out.setScopes(scopesOf(text, defaultScopes));
        out.setSourceText(matcher.group());
        double[] coordinate = coordinateOf(destination, anchor);
        if (coordinate == null) {
            out.setStatus(LocationConstraint.UNRESOLVED);
        } else {
            out.setLng(coordinate[0]);
            out.setLat(coordinate[1]);
            out.setStatus(LocationConstraint.RESOLVED);
        }
        return out;
    }

    public static boolean clearRequested(String text) {
        return text != null && CLEAR_WORDS.stream().anyMatch(text::contains);
    }

    public static boolean appliesTo(LocationConstraint c, String scope) {
        return c != null && (c.getScopes() == null || c.getScopes().isEmpty()
                || c.getScopes().contains(scope));
    }

    /**
     * 有坐标时严格按半径判定；联网条目没有坐标时只接受名称/地址明确包含锚点的结果。
     * 未解析锚点不执行过滤，交上层明确提示“尚未定位”。
     */
    public static boolean matches(LocationConstraint c, String scope, String name, String address,
                                  Double lng, Double lat) {
        if (!appliesTo(c, scope) || !LocationConstraint.RESOLVED.equals(c.getStatus())) {
            return true;
        }
        if (lng != null && lat != null) {
            return GeoUtils.distanceKm(c.getLng(), c.getLat(), lng, lat) <= c.getRadiusKm();
        }
        String locationText = (name == null ? "" : name) + " " + (address == null ? "" : address);
        return locationText.contains(c.getAnchorName());
    }

    public static Double distanceKm(LocationConstraint c, String scope, Double lng, Double lat) {
        if (!appliesTo(c, scope) || !LocationConstraint.RESOLVED.equals(c.getStatus())
                || lng == null || lat == null) {
            return null;
        }
        return Math.round(GeoUtils.distanceKm(c.getLng(), c.getLat(), lng, lat) * 10.0) / 10.0;
    }

    public static String summary(LocationConstraint c) {
        if (c == null) {
            return null;
        }
        if (!LocationConstraint.RESOLVED.equals(c.getStatus())) {
            return "已识别“" + c.getAnchorName() + "附近”，但当前无法确定该地点坐标";
        }
        return c.getAnchorName() + " " + trimRadius(c.getRadiusKm()) + " 公里范围内";
    }

    private static String normalizeAnchor(String raw) {
        String value = raw == null ? "" : raw.trim();
        value = value.replaceFirst("^(?:想要|希望|最好|尽量|都要|全部|主要|活动|行程|约会)", "");
        int at = Math.max(value.lastIndexOf('在'), value.lastIndexOf('离'));
        if (at >= 0 && at < value.length() - 1) {
            value = value.substring(at + 1);
        }
        value = value.replaceFirst("^(?:靠近|临近|接近|位于)", "");
        return value.replaceAll("^[的\\s]+|[的\\s]+$", "");
    }

    /**
     * 常见别名与口语纠错只在能够唯一确定地点时归一化。用户把“校区”说成“小区”时，
     * 若同时出现“南京邮电大学/南邮 + 三牌楼”，仍可确定是三牌楼校区；普通小区名称不改写。
     */
    private static String canonicalAnchor(String destination, String anchor) {
        if ("南京".equals(destination == null ? "" : destination.trim()) && anchor != null) {
            String compact = anchor.replaceAll("\\s+", "");
            boolean njupt = compact.contains("南京邮电大学") || compact.contains("南邮");
            if (njupt && compact.contains("三牌楼")) {
                return "南京邮电大学三牌楼校区";
            }
        }
        return anchor;
    }

    private static double radiusOf(String text) {
        Matcher matcher = RADIUS_PATTERN.matcher(text);
        if (!matcher.find()) {
            return DEFAULT_RADIUS_KM;
        }
        try {
            double radius = Double.parseDouble(matcher.group(1));
            return radius > 0 && radius <= 50 ? radius : DEFAULT_RADIUS_KM;
        } catch (NumberFormatException ignored) {
            return DEFAULT_RADIUS_KM;
        }
    }

    private static List<String> scopesOf(String text, List<String> defaults) {
        List<String> scopes = new ArrayList<>();
        if (containsAny(text, "景点", "景区", "游玩", "拍照")) scopes.add(ATTRACTION);
        if (containsAny(text, "饭店", "餐厅", "餐馆", "美食", "吃饭")) scopes.add(FOOD);
        if (containsAny(text, "酒店", "住宿", "民宿", "住处")) scopes.add(HOTEL);
        if (scopes.isEmpty() && defaults != null) scopes.addAll(defaults);
        return scopes.stream().distinct().toList();
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    private static double[] coordinateOf(String destination, String anchor) {
        Map<String, double[]> city = ANCHORS.get(destination == null ? "" : destination.trim());
        if (city == null) return null;
        double[] exact = city.get(anchor);
        if (exact != null) return exact;
        String normalized = anchor.toLowerCase(Locale.ROOT);
        return city.entrySet().stream()
                .filter(e -> normalized.contains(e.getKey().toLowerCase(Locale.ROOT))
                        || e.getKey().toLowerCase(Locale.ROOT).contains(normalized))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static String trimRadius(double radius) {
        return radius == Math.rint(radius) ? Integer.toString((int) radius) : Double.toString(radius);
    }

    private static Map<String, Map<String, double[]>> anchors() {
        Map<String, Map<String, double[]>> all = new LinkedHashMap<>();
        // 项目坐标统一使用 GCJ-02。三牌楼校区官方地址为新模范马路 66 号；
        // 校园中心点由公开 WGS84 坐标转换为 GCJ-02，用于“附近”半径过滤。
        double[] njuptSanpailou = new double[]{118.770844, 32.081113};
        all.put("南京", Map.ofEntries(
                Map.entry("新街口", new double[]{118.7787, 32.0417}),
                Map.entry("夫子庙", new double[]{118.7880, 32.0219}),
                Map.entry("玄武湖", new double[]{118.7960, 32.0710}),
                Map.entry("南京站", new double[]{118.7968, 32.0872}),
                Map.entry("南京南站", new double[]{118.7970, 31.9680}),
                Map.entry("总统府", new double[]{118.7950, 32.0420}),
                Map.entry("南京邮电大学三牌楼校区", njuptSanpailou),
                Map.entry("南邮三牌楼", njuptSanpailou),
                Map.entry("三牌楼", njuptSanpailou),
                Map.entry("新模范马路66号", njuptSanpailou)));
        all.put("苏州", Map.of(
                "观前街", new double[]{120.6220, 31.3120},
                "平江路", new double[]{120.6330, 31.3140},
                "金鸡湖", new double[]{120.6720, 31.3100},
                "苏州站", new double[]{120.6088, 31.3290},
                "山塘街", new double[]{120.5940, 31.3260}));
        return all;
    }
}
