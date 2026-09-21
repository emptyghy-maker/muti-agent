package com.ghy.mutiagent.rule;

/**
 * 三通道独立对话的确定性路由（零成本、不调 LLM）：
 * 候选阶段（景点/美食/酒店）的自由文本可能指向任意通道——例如美食环节用户说「酒店要便宜的」。
 * 命中单一通道 → 该通道单独重筛（latest-wins，其余通道保留并行预热结果）；
 * 零命中（与环节无关的条件，如「预算2000」）或多通道命中（整句语义跨通道）→ 返回 null，
 * 调用方沿用当前环节重筛（既有行为，不产生跨通道误判）。
 */
public final class ChannelRouter {

    public static final String CHANNEL_ATTRACTION = "ATTRACTION";
    public static final String CHANNEL_FOOD = "FOOD";
    public static final String CHANNEL_HOTEL = "HOTEL";

    private static final String[] FOOD_KEYWORDS = {
            "美食", "餐厅", "饭店", "小吃", "口味", "菜系", "人均", "火锅", "面馆", "甜点",
            "奶茶", "午饭", "晚饭", "早餐", "午餐", "晚餐", "吃的", "吃啥", "馆子", "夜宵"
    };
    private static final String[] HOTEL_KEYWORDS = {
            "酒店", "民宿", "住宿", "宾馆", "客栈", "几星", "星级", "入住", "房间", "江景房", "湖景房"
    };
    private static final String[] ATTRACTION_KEYWORDS = {
            "景点", "景区", "门票", "爬山", "夜景", "博物馆", "公园", "乐园", "寺庙", "老街",
            "游船", "玩什么", "去哪玩", "逛逛", "缆车"
    };

    private ChannelRouter() {
    }

    /** 返回目标通道（ATTRACTION/FOOD/HOTEL）；无法唯一判定返回 null（沿用当前环节） */
    public static String route(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        int food = hits(message, FOOD_KEYWORDS);
        int hotel = hits(message, HOTEL_KEYWORDS);
        int attraction = hits(message, ATTRACTION_KEYWORDS);
        int channels = (food > 0 ? 1 : 0) + (hotel > 0 ? 1 : 0) + (attraction > 0 ? 1 : 0);
        if (channels != 1) {
            return null;
        }
        if (food > 0) {
            return CHANNEL_FOOD;
        }
        if (hotel > 0) {
            return CHANNEL_HOTEL;
        }
        return CHANNEL_ATTRACTION;
    }

    private static int hits(String message, String[] keywords) {
        int n = 0;
        for (String kw : keywords) {
            if (message.contains(kw)) {
                n++;
            }
        }
        return n;
    }
}
