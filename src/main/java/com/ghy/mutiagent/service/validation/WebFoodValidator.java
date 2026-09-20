package com.ghy.mutiagent.service.validation;

import com.ghy.mutiagent.model.WebFoodCandidate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 联网美食结果入库前的确定性校验（防恶意写入）。
 *
 * 模型输出是不可信数据：SQL 注入由 MyBatis-Plus 参数化 insert 兜底，
 * 本校验负责语义与内容安全——长度上限、数值区间、控制字符与脚本/注释类载荷一律拒绝。
 * 拒绝原因以校验码形式写入 t_web_food_audit，可追溯、可事后人工复核。
 */
public final class WebFoodValidator {

    public static final int MAX_NAME = 128;
    public static final int MAX_CUISINE = 32;
    public static final int MAX_ADDRESS = 128;
    public static final int MAX_WHY = 200;

    /** 阻断标记：控制字符之外的脚本/标记/注释载荷（宁可错杀，不可入库） */
    private static final String[] BLOCKED = {
            "<", ">", "\"", "'", "`", "/*", "*/", "--", ";",
            "javascript", "script", "onclick", "onerror", "onload", "expression",
            "select ", "insert ", "update ", "delete ", "drop ", "union "
    };

    private WebFoodValidator() {
    }

    /** 返回拒绝原因列表；空列表 = 通过（可入库） */
    public static List<String> validate(WebFoodCandidate w) {
        List<String> reasons = new ArrayList<>();
        if (w == null) {
            reasons.add("EMPTY_ITEM");
            return reasons;
        }
        String name = trim(w.getName());
        if (name.length() < 2 || name.length() > MAX_NAME || !cleanText(name)) {
            reasons.add("NAME_INVALID");
        }
        String cuisine = trim(w.getCuisine());
        if (cuisine.length() < 2 || cuisine.length() > MAX_CUISINE || !cleanText(cuisine)) {
            reasons.add("CUISINE_INVALID");
        }
        BigDecimal price = w.getAvgPrice();
        if (price == null || price.compareTo(BigDecimal.ONE) < 0
                || price.compareTo(new BigDecimal("10000")) > 0) {
            reasons.add("PRICE_INVALID");
        }
        String address = trim(w.getAddress());
        if (!address.isEmpty() && (address.length() > MAX_ADDRESS || !cleanText(address))) {
            reasons.add("ADDRESS_INVALID");
        }
        String why = trim(w.getWhy());
        if (!why.isEmpty() && (why.length() > MAX_WHY || !cleanText(why))) {
            reasons.add("WHY_INVALID");
        }
        return reasons;
    }

    /** 文本安全：无控制字符、无脚本/标记/注释类载荷 */
    public static boolean cleanText(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        String lower = s.toLowerCase();
        for (String b : BLOCKED) {
            if (lower.contains(b)) {
                return false;
            }
        }
        return true;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
