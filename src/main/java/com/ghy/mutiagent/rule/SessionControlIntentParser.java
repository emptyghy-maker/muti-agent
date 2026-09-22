package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.enums.TravelStage;

import java.util.Set;

/**
 * 会话级控制指令解析器。
 *
 * 控制指令必须在需求抽取和阶段 Agent 之前识别；只接受短句和明确措辞，避免把普通旅游需求误判为控制动作。
 */
public final class SessionControlIntentParser {

    public enum Type {
        NONE,
        RESTART,
        RESELECT_CURRENT,
        GO_BACK,
        RESELECT_STAGE
    }

    public record Intent(Type type, TravelStage targetStage) {
        public static Intent none() {
            return new Intent(Type.NONE, null);
        }
    }

    private static final Set<String> RESTART = Set.of(
            "重新开始", "重新开始规划", "全部重新开始", "从头开始", "全部重来", "重新来",
            "重来", "从头再来", "再来一遍", "重新规划", "重新问", "重新收集", "重填");
    private static final Set<String> RESELECT_CURRENT = Set.of(
            "重新选择", "重新选", "清空重选", "选错了", "我选错了", "重新挑选");
    private static final Set<String> GO_BACK = Set.of(
            "上一步", "返回上一步", "回到上一步", "返回上一环节", "回到上一环节");

    private SessionControlIntentParser() {
    }

    public static Intent parse(String message) {
        if (message == null || message.isBlank()) {
            return Intent.none();
        }
        String normalized = message.replaceAll("[\\s，。！？、,.!?；;：:]", "").trim();
        if (normalized.length() > 20) {
            return Intent.none();
        }
        if (RESTART.contains(normalized)) {
            return new Intent(Type.RESTART, null);
        }
        if (containsReselect(normalized, "景点")) {
            return new Intent(Type.RESELECT_STAGE, TravelStage.ATTRACTIONS);
        }
        if (containsReselect(normalized, "美食") || containsReselect(normalized, "餐厅")) {
            return new Intent(Type.RESELECT_STAGE, TravelStage.FOODS);
        }
        if (containsReselect(normalized, "酒店") || containsReselect(normalized, "住宿")) {
            return new Intent(Type.RESELECT_STAGE, TravelStage.HOTELS);
        }
        if (RESELECT_CURRENT.contains(normalized)) {
            return new Intent(Type.RESELECT_CURRENT, null);
        }
        if (GO_BACK.contains(normalized)) {
            return new Intent(Type.GO_BACK, null);
        }
        return Intent.none();
    }

    private static boolean containsReselect(String text, String subject) {
        return text.contains(subject)
                && (text.contains("重新选") || text.contains("重选") || text.contains("重新挑")
                || text.contains("选错") || text.contains("返回选"));
    }
}
