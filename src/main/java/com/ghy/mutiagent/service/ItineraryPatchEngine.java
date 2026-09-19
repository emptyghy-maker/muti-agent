package com.ghy.mutiagent.service;

import com.ghy.mutiagent.model.AdjustPatchIntent;
import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.PatchOperation;
import com.ghy.mutiagent.model.PlanNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * S09 补丁的纯确定性逻辑（不依赖 Spring/DB，可单测）：
 * 白名单操作验证、范围验证、在副本上应用补丁、未触及节点语义比较。
 * 提示词不是安全边界：这里逐项验证操作类型、nodeId 存在、范围与未触及节点不变。
 */
public final class ItineraryPatchEngine {

    public static final String ERR_PATCH_OUT_OF_SCOPE = "PATCH_OUT_OF_SCOPE";
    public static final String ERR_PATCH_OP_UNSUPPORTED = "PATCH_OP_UNSUPPORTED";
    public static final String ERR_PATCH_NODE_NOT_FOUND = "PATCH_NODE_NOT_FOUND";
    public static final String ERR_PATCH_UNTOUCHED_CHANGED = "PATCH_UNTOUCHED_CHANGED";
    public static final String ERR_PATCH_CANDIDATE_NOT_IN_POOL = "PATCH_CANDIDATE_NOT_IN_POOL";
    public static final String ERR_GLOBAL_BUDGET_EXCEEDED = "GLOBAL_BUDGET_EXCEEDED";
    public static final String ERR_PATCH_VALIDATION_FAILED = "PATCH_VALIDATION_FAILED";
    public static final String ERR_REVISION_CONFLICT = "REVISION_CONFLICT";

    private ItineraryPatchEngine() {
    }

    /** 定位节点：返回 {day, node}；不存在返回 null */
    public static record LocatedNode(DailyPlan day, PlanNode node) {
    }

    public static LocatedNode findNode(ItineraryPlan plan, String nodeId) {
        if (plan == null || plan.getDays() == null || nodeId == null) {
            return null;
        }
        for (DailyPlan d : plan.getDays()) {
            if (d.getNodes() == null) {
                continue;
            }
            for (PlanNode n : d.getNodes()) {
                if (nodeId.equals(n.getNodeId())) {
                    return new LocatedNode(d, n);
                }
            }
        }
        return null;
    }

    /**
     * 逐项验证模型补丁：操作白名单、nodeId 存在、范围（所在天必须 ∈ targetDays）。
     * 返回首个违规描述（null = 全部通过）。
     */
    public static String validateOps(ItineraryPlan plan, AdjustPatchIntent intent) {
        if (intent == null || intent.getOperations() == null || intent.getOperations().isEmpty()) {
            return "补丁没有可执行的操作";
        }
        List<Integer> scope = intent.getTargetDays() == null ? List.of() : intent.getTargetDays();
        for (PatchOperation op : intent.getOperations()) {
            if (!PatchOperation.OP_REMOVE.equals(op.getOp())
                    && !PatchOperation.OP_REPLACE_PLACE.equals(op.getOp())) {
                return "不支持的操作类型：" + op.getOp();
            }
            LocatedNode located = findNode(plan, op.getNodeId());
            if (located == null) {
                return "节点不存在：" + op.getNodeId();
            }
            if (!scope.contains(located.day().getDayIndex())) {
                return "操作超出声明范围（targetDays=" + scope + "）：" + op.getNodeId();
            }
            if (PatchOperation.OP_REPLACE_PLACE.equals(op.getOp())
                    && (op.getPlaceKey() == null || op.getPlaceKey().isBlank())) {
                return "REPLACE_PLACE 缺少 placeKey：" + op.getNodeId();
            }
        }
        return null;
    }

    /** 在计划副本上应用补丁（先深拷贝再改），返回触及的 nodeId 列表 */
    public static List<String> applyOperations(ItineraryPlan plan, List<PatchOperation> operations,
                                               Map<String, Long> resolvedPlaceIds,
                                               Map<Long, String> resolvedNames) {
        List<String> touched = new ArrayList<>();
        for (PatchOperation op : operations) {
            LocatedNode located = findNode(plan, op.getNodeId());
            if (located == null) {
                continue;
            }
            switch (op.getOp()) {
                case PatchOperation.OP_REMOVE -> located.day().getNodes().remove(located.node());
                case PatchOperation.OP_REPLACE_PLACE -> {
                    Long newId = resolvedPlaceIds.get(op.getPlaceKey());
                    located.node().setPlaceId(newId);
                    located.node().setName(resolvedNames.get(newId));
                }
                default -> { }
            }
            touched.add(op.getNodeId());
        }
        return touched;
    }

    /**
     * 未触及节点语义比较：除 touched 外的每个节点在补丁前后必须完全一致
     * （地点、活动、时间、交通、费用、住宿、备注与稳定身份；seq 是展示序号同样比对）。
     * 返回不一致的 nodeId 列表（空 = 未触及节点全部保持不变）。
     */
    public static List<String> compareUntouched(ItineraryPlan before, ItineraryPlan after,
                                                List<String> touched) {
        List<String> mismatches = new ArrayList<>();
        Map<String, LocatedNode> beforeMap = index(before);
        Map<String, LocatedNode> afterMap = index(after);
        for (Map.Entry<String, LocatedNode> e : beforeMap.entrySet()) {
            String nodeId = e.getKey();
            if (touched != null && touched.contains(nodeId)) {
                continue;
            }
            LocatedNode b = e.getValue();
            LocatedNode a = afterMap.get(nodeId);
            if (a == null || !sameSemantics(b.node(), a.node())) {
                mismatches.add(nodeId);
            }
        }
        return mismatches;
    }

    private static boolean sameSemantics(PlanNode a, PlanNode b) {
        return eq(a.getType(), b.getType())
                && eq(a.getPlaceId(), b.getPlaceId())
                && eq(a.getName(), b.getName())
                && eq(a.getTime(), b.getTime())
                && eq(a.getNote(), b.getNote())
                && eq(a.getSeq(), b.getSeq())
                && eq(a.getDurationMinutes(), b.getDurationMinutes())
                && eq(a.getTravelMinutes(), b.getTravelMinutes())
                && eq(a.getDepartTime(), b.getDepartTime())
                && eq(a.getNodeId(), b.getNodeId());
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static Map<String, LocatedNode> index(ItineraryPlan plan) {
        java.util.LinkedHashMap<String, LocatedNode> map = new java.util.LinkedHashMap<>();
        for (DailyPlan d : plan.getDays()) {
            if (d.getNodes() == null) {
                continue;
            }
            for (PlanNode n : d.getNodes()) {
                if (n.getNodeId() != null) {
                    map.put(n.getNodeId(), new LocatedNode(d, n));
                }
            }
        }
        return map;
    }
}
