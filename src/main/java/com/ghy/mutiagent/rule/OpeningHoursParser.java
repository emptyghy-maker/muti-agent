package com.ghy.mutiagent.rule;

import java.util.ArrayList;
import java.util.List;

/**
 * 营业时间解析（S04）：Attraction.openTime 是自由文本。
 * 支持 "09:00-17:00" 与 "09:00-12:00,14:00-17:00"；无法解析记 UNKNOWN（P0 不接外部查询）。
 * 游览可行性要求「整段停留完全处于一个开放区间」，不能只验证到达时刻。
 */
public final class OpeningHoursParser {

    public enum Status {
        /** 整段停留落在某个开放区间内 */
        COVERED,
        /** 停留跨越闭馆/营业时间不符 */
        CONFLICT,
        /** 营业信息未知/无法解析 */
        UNKNOWN
    }

    private OpeningHoursParser() {
    }

    /** 解析为分钟区间列表；无法解析返回空列表（UNKNOWN） */
    public static List<int[]> parse(String openTime) {
        List<int[]> intervals = new ArrayList<>();
        if (openTime == null || openTime.isBlank()) {
            return intervals;
        }
        for (String seg : openTime.split("[,，;；]")) {
            String[] pair = seg.trim().split("[-—~到至]");
            if (pair.length != 2) {
                return List.of();
            }
            Integer from = toMin(pair[0].trim());
            Integer to = toMin(pair[1].trim());
            if (from == null || to == null || to <= from || to > 24 * 60) {
                return List.of();
            }
            intervals.add(new int[]{from, to});
        }
        return intervals;
    }

    /** [arriveMin, endMin] 是否完全处于一个开放区间 */
    public static Status covers(String openTime, int arriveMin, int endMin) {
        List<int[]> intervals = parse(openTime);
        if (intervals.isEmpty()) {
            return Status.UNKNOWN;
        }
        for (int[] it : intervals) {
            if (arriveMin >= it[0] && endMin <= it[1]) {
                return Status.COVERED;
            }
        }
        return Status.CONFLICT;
    }

    private static Integer toMin(String hm) {
        String[] p = hm.split(":");
        if (p.length != 2) {
            return null;
        }
        try {
            int h = Integer.parseInt(p[0].trim());
            int m = Integer.parseInt(p[1].trim());
            if (h < 0 || h > 24 || m < 0 || m > 59) {
                return null;
            }
            return h * 60 + m;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
