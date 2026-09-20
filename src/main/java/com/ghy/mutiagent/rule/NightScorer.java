package com.ghy.mutiagent.rule;

import java.util.List;

/**
 * 夜景系数（确定性）：标签加权求和，用于「多个夜景景点时选系数最高者进入夜晚时段」。
 * 权重口径：夜景（核心）×3、音乐喷泉/夜游/游船（夜间体验）×2、灯光/摩天轮（夜间辅助）×1。
 */
public final class NightScorer {

    private NightScorer() {
    }

    public static int score(String tags) {
        if (tags == null || tags.isBlank()) {
            return 0;
        }
        int s = 0;
        for (String t : split(tags)) {
            switch (t) {
                case "夜景" -> s += 3;
                case "音乐喷泉", "夜游", "游船", "游船夜游" -> s += 2;
                case "灯光", "灯光秀", "摩天轮" -> s += 1;
                default -> { }
            }
        }
        return s;
    }

    /** 是否为夜景标签景点（夜景系数 > 0） */
    public static boolean isNight(String tags) {
        return score(tags) > 0;
    }

    private static List<String> split(String tags) {
        return List.of(tags.split("[,，、;；]"));
    }
}
