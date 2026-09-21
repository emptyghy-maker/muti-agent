package com.ghy.mutiagent.service.validation;

import com.ghy.mutiagent.model.WebHotelCandidate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 网搜酒店入库校验（确定性，防恶意写入）：名称/价格/地址/标签为硬门槛——
 * 价格缺失或非法直接拒绝（住宿费用必须可核实，否则账单无法计算），
 * tags 缺失拒绝（跨会话复用时硬约束过滤依赖结构化标签）。
 *
 * 模型输出是不可信数据：SQL 注入由 MyBatis-Plus 参数化 insert 兜底，
 * 本校验负责语义与内容安全——长度上限、数值区间、控制字符与脚本/注释类载荷一律拒绝
 * （与 WebFoodValidator 同口径）。拒绝原因以校验码写入 t_web_food_audit。
 */
public final class WebHotelValidator {

    public static final int MAX_NAME = 128;
    public static final int MAX_LEVEL = 16;
    public static final int MAX_TAGS = 255;
    public static final int MAX_ADDRESS = 128;
    public static final int MAX_WHY = 200;
    /** 每晚价格上限：超过视为明显异常数据 */
    public static final BigDecimal MAX_PRICE = new BigDecimal("100000");

    private WebHotelValidator() {
    }

    /** 返回拒绝原因列表；空列表 = 通过（可入库） */
    public static List<String> validate(WebHotelCandidate w) {
        List<String> reasons = new ArrayList<>();
        if (w == null) {
            reasons.add("EMPTY_ITEM");
            return reasons;
        }
        String name = trim(w.getName());
        if (name.length() < 2 || name.length() > MAX_NAME || !WebFoodValidator.cleanText(name)) {
            reasons.add("NAME_INVALID");
        }
        BigDecimal price = w.getPricePerNight();
        if (price == null || price.signum() <= 0 || price.compareTo(MAX_PRICE) > 0) {
            reasons.add("INVALID_PRICE");
        }
        String level = trim(w.getLevel());
        if (!level.isEmpty() && (level.length() > MAX_LEVEL || !WebFoodValidator.cleanText(level))) {
            reasons.add("LEVEL_INVALID");
        }
        String tags = trim(w.getTags());
        if (tags.isEmpty()) {
            reasons.add("MISSING_TAGS");
        } else if (tags.length() > MAX_TAGS || !WebFoodValidator.cleanText(tags)) {
            reasons.add("TAGS_INVALID");
        }
        String address = trim(w.getAddress());
        if (address.isEmpty()) {
            reasons.add("MISSING_ADDRESS");
        } else if (address.length() > MAX_ADDRESS || !WebFoodValidator.cleanText(address)) {
            reasons.add("ADDRESS_INVALID");
        }
        String why = trim(w.getWhy());
        if (!why.isEmpty() && (why.length() > MAX_WHY || !WebFoodValidator.cleanText(why))) {
            reasons.add("WHY_INVALID");
        }
        return reasons;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
