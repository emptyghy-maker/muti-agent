package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.model.TravelState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 偏好字段应用（纯逻辑）：把规则/LLM 解析出的字符串值转换成类型化字段并标记三态。
 * specialRequests 为累积语义：新内容与既有内容合并追加，不覆盖（S02：禁止每轮覆盖原有有效需求）。
 */
public final class PreferenceUpdater {

    private static final Logger log = LoggerFactory.getLogger(PreferenceUpdater.class);

    private PreferenceUpdater() {
    }

    public static void apply(TravelState state, Map<String, String> updates) {
        TravelPreference p = state.getPreference();
        for (Map.Entry<String, String> e : updates.entrySet()) {
            String field = e.getKey();
            String value = e.getValue() == null ? "" : e.getValue().trim();
            if (value.isEmpty()) {
                continue;
            }
            boolean unsure = "UNSURE".equalsIgnoreCase(value);
            switch (field) {
                case "days" -> {
                    if (unsure) {
                        p.setDays(null);
                        p.markDefaulted("days");
                    } else {
                        Integer v = firstInt(value);
                        if (v != null && v >= 1 && v <= 15) {
                            p.setDays(v);
                            p.markConfirmed("days");
                        }
                    }
                }
                case "totalBudget" -> {
                    if (unsure) {
                        p.setTotalBudget(null);
                        p.markDefaulted("totalBudget");
                    } else {
                        BigDecimal v = firstNumber(value);
                        if (v != null && v.signum() > 0) {
                            p.setTotalBudget(v);
                            p.markConfirmed("totalBudget");
                        }
                    }
                }
                case "peopleCount" -> {
                    if (unsure) {
                        p.setPeopleCount(null);
                        p.markDefaulted("peopleCount");
                    } else {
                        Integer v = firstInt(value);
                        if (v != null && v >= 1 && v <= 20) {
                            p.setPeopleCount(v);
                            p.markConfirmed("peopleCount");
                        }
                    }
                }
                case "attractionType" -> {
                    if (unsure) {
                        p.setAttractionType(null);
                        p.markDefaulted("attractionType");
                    } else if (value.contains("娱乐") || value.contains("游乐")) {
                        p.setAttractionType("娱乐项目");
                        p.markConfirmed("attractionType");
                    } else if (value.contains("打卡") || value.contains("拍照")) {
                        p.setAttractionType("打卡拍照");
                        p.markConfirmed("attractionType");
                    } else {
                        p.setAttractionType("混合");
                        p.markConfirmed("attractionType");
                    }
                }
                case "foodTaste" -> {
                    if (unsure) {
                        p.setFoodTaste(null);
                        p.markDefaulted("foodTaste");
                    } else if (value.contains("清淡") || value.contains("不辣")) {
                        p.setFoodTaste("清淡");
                        p.markConfirmed("foodTaste");
                    } else if (value.contains("辣")) {
                        p.setFoodTaste("辣");
                        p.markConfirmed("foodTaste");
                    } else if (value.contains("本地") || value.contains("地道") || value.contains("特色")) {
                        p.setFoodTaste("本地特色菜");
                        p.markConfirmed("foodTaste");
                    } else {
                        p.setFoodTaste(value);
                        p.markConfirmed("foodTaste");
                    }
                }
                case "energyLevel" -> {
                    if (unsure) {
                        p.setEnergyLevel(null);
                        p.markDefaulted("energyLevel");
                    } else if (value.contains("好")) {
                        p.setEnergyLevel("体力好");
                        p.markConfirmed("energyLevel");
                    } else if (value.contains("弱") || value.contains("差")) {
                        p.setEnergyLevel("偏弱");
                        p.markConfirmed("energyLevel");
                    } else {
                        p.setEnergyLevel("一般");
                        p.markConfirmed("energyLevel");
                    }
                }
                case "hotelStyle" -> {
                    if (unsure) {
                        p.setHotelStyle(null);
                        p.markDefaulted("hotelStyle");
                    } else if (value.contains("体验")) {
                        p.setHotelStyle("体验优先");
                        p.markConfirmed("hotelStyle");
                    } else if (value.contains("位置") || value.contains("交通")) {
                        p.setHotelStyle("位置优先");
                        p.markConfirmed("hotelStyle");
                    } else {
                        p.setHotelStyle("性价比优先");
                        p.markConfirmed("hotelStyle");
                    }
                }
                case "specialRequests" -> {
                    if (unsure) {
                        p.setSpecialRequests("");
                        p.markDefaulted("specialRequests");
                    } else {
                        String existing = p.getSpecialRequests();
                        p.setSpecialRequests(existing == null || existing.isBlank() || existing.contains(value)
                                ? value
                                : existing + "；" + value);
                        p.markConfirmed("specialRequests");
                    }
                }
                case "breakfastPerDay" -> {
                    if (unsure) {
                        p.setMealPlan(null);
                        p.markDefaulted("mealPlan");
                    } else {
                        Integer v = firstInt(value);
                        if (v != null && v >= 0 && v <= 5) {
                            mealPlanOf(p).setBreakfastPerDay(v);
                            p.markConfirmed("mealPlan");
                        }
                    }
                }
                case "lunchPerDay" -> {
                    Integer v = firstInt(value);
                    if (v != null && v >= 0 && v <= 5) {
                        mealPlanOf(p).setLunchPerDay(v);
                        p.markConfirmed("mealPlan");
                    }
                }
                case "dinnerPerDay" -> {
                    Integer v = firstInt(value);
                    if (v != null && v >= 0 && v <= 5) {
                        mealPlanOf(p).setDinnerPerDay(v);
                        p.markConfirmed("mealPlan");
                    }
                }
                case "snacksAllowed" -> {
                    if (unsure) {
                        p.setMealPlan(null);
                        p.markDefaulted("mealPlan");
                    } else {
                        mealPlanOf(p).setSnacksAllowed("true".equalsIgnoreCase(value)
                                || "1".equals(value));
                        p.markConfirmed("mealPlan");
                    }
                }
                case "wakeTime" -> {
                    // 问卷起床时间：HH:mm，05:00-12:00（聊天兜底解析已归一）
                    if (value.matches("^([01]?\\d|2[0-3]):[0-5]\\d$")
                            && value.compareTo("05:00") >= 0 && value.compareTo("12:00") <= 0) {
                        p.setWakeTime(value);
                        p.markConfirmed("wakeTime");
                    }
                }
                case "returnDeadline" -> {
                    // 问卷回家/回酒店截止：HH:mm（17:00-24:00）或 UNLIMITED
                    if ("UNLIMITED".equalsIgnoreCase(value)
                            || (value.matches("^([01]?\\d|2[0-3]):[0-5]\\d$")
                            && value.compareTo("17:00") >= 0 && value.compareTo("24:00") <= 0)) {
                        p.setReturnDeadline("UNLIMITED".equalsIgnoreCase(value) ? "UNLIMITED" : value);
                        p.markConfirmed("returnDeadline");
                    }
                }
                case "activityBias" -> {
                    if ("MORNING".equals(value) || "BALANCED".equals(value) || "EVENING".equals(value)) {
                        p.setActivityBias(value);
                        p.markConfirmed("activityBias");
                    }
                }
                case "nightPlan" -> {
                    if ("ONE".equals(value) || "ALL".equals(value)) {
                        p.setNightPlan(value);
                        p.markConfirmed("nightPlan");
                    }
                }
                default -> log.debug("[TravelAgent] 忽略未知偏好字段: {}", field);
            }
            state.getAskedFields().add(field);
        }
    }

    private static Integer firstInt(String s) {
        Matcher m = Pattern.compile("(\\d+)").matcher(s);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** 餐次结构懒创建：餐次字段更新按需初始化 mealPlan，不覆盖已有内容 */
    private static TravelPreference.MealPlan mealPlanOf(TravelPreference p) {
        if (p.getMealPlan() == null) {
            p.setMealPlan(new TravelPreference.MealPlan());
        }
        return p.getMealPlan();
    }

    private static BigDecimal firstNumber(String s) {
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(s);
        return m.find() ? new BigDecimal(m.group(1)) : null;
    }
}
