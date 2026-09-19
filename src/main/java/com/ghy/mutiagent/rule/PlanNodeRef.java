package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.PlanNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 计划节点寻址（S08）：为「修复输入/违规定位」提供确定性的节点引用。
 * 格式 d{dayIndex}-{typeInitial}{typeOrdinal}，如 d2-a1 = 第 2 天第 1 个 attraction 节点。
 * 序号按天内节点顺序（时间排序后）计；类型缩写：t=transport h=hotel a=attraction r=restaurant s=rest x=其他。
 * 注意：本引用用于修复上下文与违规定位，不是持久化身份；S09 的稳定 nodeId 落地后按同口径对齐。
 */
public final class PlanNodeRef {

    private PlanNodeRef() {
    }

    public static String ref(int dayIndex, String type, int typeOrdinal) {
        return "d" + dayIndex + "-" + initial(type) + typeOrdinal;
    }

    /** 与 nodes 对齐的节点引用列表（同类型节点按出现顺序 1..n 编号） */
    public static List<String> refs(List<PlanNode> nodes, int dayIndex) {
        java.util.Map<String, Integer> ordinals = new java.util.HashMap<>();
        List<String> out = new ArrayList<>(nodes.size());
        for (PlanNode n : nodes) {
            String type = n.getType() == null ? "x" : n.getType();
            int ordinal = ordinals.merge(type, 1, Integer::sum);
            out.add(ref(dayIndex, type, ordinal));
        }
        return out;
    }

    public static int dayOf(String ref) {
        if (ref == null || !ref.startsWith("d") || !ref.contains("-")) {
            return 0;
        }
        try {
            return Integer.parseInt(ref.substring(1, ref.indexOf('-')));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String initial(String type) {
        if (type == null) {
            return "x";
        }
        return switch (type) {
            case "transport" -> "t";
            case "hotel" -> "h";
            case "attraction" -> "a";
            case "restaurant" -> "r";
            case "rest" -> "s";
            default -> "x";
        };
    }
}
