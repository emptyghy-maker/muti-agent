package com.ghy.mutiagent.rule;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 标签匹配器（标签评分系统）：
 * - 需求标签（needTags，来自确定性规则 + 需求分析 Agent，受控词表）与实体标签精确求交 → 命中加分；
 * - 内置反义/互斥标签对（如 安静↔热闹、舒适↔强度高、平价↔高档）→ 冲突减分；
 * - 加分 = min(命中数 × 0.4, 1.2)，减分 = 冲突数 × 0.6；整体加成为二者之差（[-1.8, +1.2] 区间）。
 */
public final class TagMatcher {

    /** 命中加分（每标签）与封顶 */
    public static final double HIT_BONUS = 0.4;
    public static final double HIT_CAP = 1.2;
    /** 冲突减分（每冲突对） */
    public static final double CONFLICT_PENALTY = 0.6;

    /** 反义/互斥标签对（双向生效）：需求侧含任一端、实体含另一端即计一次冲突 */
    private static final List<String[]> CONFLICT_PAIRS = List.of(
            new String[]{"安静", "热闹"},
            new String[]{"小众", "热闹"},
            new String[]{"舒适", "热闹"},
            new String[]{"安静", "演出"},
            new String[]{"舒适", "强度高"},
            new String[]{"轻松", "强度高"},
            new String[]{"舒适", "刺激"},
            new String[]{"轻松", "刺激"},
            new String[]{"平价", "高档"},
            new String[]{"低价", "高档"},
            new String[]{"省钱", "高档"},
            new String[]{"免费", "高档"},
            new String[]{"亲子", "酒吧"},
            new String[]{"亲子", "夜生活"},
            new String[]{"安静", "亲子乐园"},
            new String[]{"情侣", "亲子乐园"});

    private TagMatcher() {
    }

    /** 解析逗号分隔标签串（忽略空值/空白） */
    public static Set<String> parse(String tags) {
        Set<String> out = new LinkedHashSet<>();
        if (tags == null || tags.isBlank()) {
            return out;
        }
        for (String t : tags.split("[,，、;；]")) {
            String v = t.trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    /** 一次匹配结论：命中标签数与冲突数 */
    public record Verdict(int hits, int conflicts) {

        public double bonus() {
            return Math.min(hits * HIT_BONUS, HIT_CAP) - conflicts * CONFLICT_PENALTY;
        }

        /** 展示用说明（如「+1.2 情侣/夜景 · −0.6 热闹」）；无加成返回空串 */
        public String note(Set<String> hitTags, Set<String> conflictTags) {
            if (hits == 0 && conflicts == 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            if (hits > 0) {
                sb.append('+').append(round1(Math.min(hits * HIT_BONUS, HIT_CAP))).append(' ')
                        .append(String.join("/", hitTags));
            }
            if (conflicts > 0) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append('−').append(round1(conflicts * CONFLICT_PENALTY)).append(' ')
                        .append(String.join("/", conflictTags));
            }
            return sb.toString();
        }
    }

    private static String round1(double v) {
        return String.format("%.1f", v).replaceAll("\\.0$", "");
    }

    /** 需求标签 vs 实体标签：命中与冲突（冲突按标签对逐对计数，同一对只计一次） */
    public static Verdict evaluate(Set<String> needTags, Set<String> entityTags) {
        if (needTags.isEmpty() || entityTags.isEmpty()) {
            return new Verdict(0, 0);
        }
        Set<String> hit = new LinkedHashSet<>();
        for (String n : needTags) {
            if (entityTags.contains(n)) {
                hit.add(n);
            }
        }
        Set<String> conflict = new LinkedHashSet<>();
        for (String[] pair : CONFLICT_PAIRS) {
            String a = pair[0];
            String b = pair[1];
            boolean needA = needTags.contains(a) || needTags.contains(b);
            if (!needA) {
                continue;
            }
            boolean entityOther = needTags.contains(a) ? entityTags.contains(b) : entityTags.contains(a);
            if (entityOther) {
                conflict.add(b);
            }
        }
        return new Verdict(hit.size(), conflict.size());
    }

    /** 实体标签命中说明（供前端 tooltip）：命中标签与冲突标签 */
    public static Set<String> hitTags(Set<String> needTags, Set<String> entityTags) {
        Set<String> hit = new LinkedHashSet<>();
        for (String n : needTags) {
            if (entityTags.contains(n)) {
                hit.add(n);
            }
        }
        return hit;
    }

    public static Set<String> conflictTags(Set<String> needTags, Set<String> entityTags) {
        Set<String> conflict = new LinkedHashSet<>();
        for (String[] pair : CONFLICT_PAIRS) {
            String a = pair[0];
            String b = pair[1];
            boolean needA = needTags.contains(a) || needTags.contains(b);
            if (!needA) {
                continue;
            }
            if (needTags.contains(a) ? entityTags.contains(b) : entityTags.contains(a)) {
                conflict.add(b);
            }
        }
        return conflict;
    }

    /** 兜底：老数据无标签时按名称/分类/特色文本做子串匹配（与旧逻辑一致） */
    public static Verdict evaluateByText(List<String> needTags, String text) {
        if (needTags.isEmpty() || text == null || text.isBlank()) {
            return new Verdict(0, 0);
        }
        String t = text.toLowerCase();
        int hits = 0;
        for (String tag : needTags) {
            if (t.contains(tag)) {
                hits++;
            }
        }
        return new Verdict(Math.min(hits, 3), 0);
    }
}
