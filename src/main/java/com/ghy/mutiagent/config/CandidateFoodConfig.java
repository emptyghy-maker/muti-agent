package com.ghy.mutiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 美食候选数量配置（application-dev.yml 的 candidate.food）。
 *
 * 一天按 3 餐算（1 早餐 + 2 正餐）：
 * 目标候选数 = max(min-total, (breakfast-per-day + main-meal-per-day) × 天数)，
 * 上限为候选池大小（数据库里有多少就给多少）。
 */
@Component
@ConfigurationProperties(prefix = "candidate.food")
public class CandidateFoodConfig {

    /** 每天的早餐候选数 */
    private int breakfastPerDay = 3;
    /** 每天的正餐候选数 */
    private int mainMealPerDay = 6;
    /** 最低总候选数（天数少时按此保底） */
    private int minTotal = 15;

    public int getBreakfastPerDay() {
        return breakfastPerDay;
    }

    public void setBreakfastPerDay(int breakfastPerDay) {
        this.breakfastPerDay = breakfastPerDay;
    }

    public int getMainMealPerDay() {
        return mainMealPerDay;
    }

    public void setMainMealPerDay(int mainMealPerDay) {
        this.mainMealPerDay = mainMealPerDay;
    }

    public int getMinTotal() {
        return minTotal;
    }

    public void setMinTotal(int minTotal) {
        this.minTotal = minTotal;
    }

    /** 按天数计算目标候选数（不超过池大小） */
    public int resolveTarget(int days, int poolSize) {
        int d = days <= 0 ? 2 : days;
        int byMeals = (breakfastPerDay + mainMealPerDay) * d;
        return Math.min(Math.max(minTotal, byMeals), poolSize);
    }
}
