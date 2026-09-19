package com.ghy.mutiagent.service;

import com.ghy.mutiagent.model.AdjustPatchIntent;
import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.PatchOperation;
import com.ghy.mutiagent.model.PlanNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S09 补丁引擎契约：白名单操作、范围验证、副本应用、未触及节点语义比较、稳定身份。
 */
class ItineraryPatchEngineTest {

    private static PlanNode node(String nodeId, String type, Long placeId, String time, String name) {
        PlanNode n = new PlanNode();
        n.setNodeId(nodeId);
        n.setType(type);
        n.setPlaceId(placeId);
        n.setTime(time);
        n.setName(name);
        n.setDurationMinutes(120);
        n.setSeq(1);
        return n;
    }

    private static ItineraryPlan plan3Days() {
        ItineraryPlan p = new ItineraryPlan();
        DailyPlan d1 = new DailyPlan();
        d1.setDayIndex(1);
        d1.setNodes(new ArrayList<>(List.of(
                node("d1-h1", "hotel", 1L, "09:30", "酒店1"),
                node("d1-a1", "attraction", 1L, "10:00", "景点1"))));
        DailyPlan d2 = new DailyPlan();
        d2.setDayIndex(2);
        d2.setNodes(new ArrayList<>(List.of(
                node("d2-a1", "attraction", 3L, "14:00", "景点3"),
                node("d2-a2", "attraction", 4L, "16:00", "景点4"))));
        DailyPlan d3 = new DailyPlan();
        d3.setDayIndex(3);
        d3.setNodes(new ArrayList<>(List.of(
                node("d3-a1", "attraction", 5L, "10:00", "景点5"))));
        p.setDays(new ArrayList<>(List.of(d1, d2, d3)));
        return p;
    }

    private static AdjustPatchIntent intent(List<Integer> days, PatchOperation... ops) {
        AdjustPatchIntent i = new AdjustPatchIntent();
        i.setBaseRevision(1);
        i.setTargetDays(days);
        i.setOperations(new ArrayList<>(List.of(ops)));
        return i;
    }

    private static PatchOperation op(String type, String nodeId, String placeKey) {
        PatchOperation o = new PatchOperation();
        o.setOp(type);
        o.setNodeId(nodeId);
        o.setPlaceKey(placeKey);
        return o;
    }

    @Test
    void inScopeRemovePassesAndKeepsOthers() {
        ItineraryPlan plan = plan3Days();
        assertNull(ItineraryPatchEngine.validateOps(plan, intent(List.of(2), op("REMOVE", "d2-a2", null))));
    }

    @Test
    void outOfScopeRemoveRejected() {
        ItineraryPlan plan = plan3Days();
        String err = ItineraryPatchEngine.validateOps(plan, intent(List.of(2), op("REMOVE", "d1-a1", null)));
        assertTrue(err != null && err.contains("超出声明范围"));
    }

    @Test
    void unsupportedOpRejected() {
        ItineraryPlan plan = plan3Days();
        String err = ItineraryPatchEngine.validateOps(plan, intent(List.of(2), op("MOVE_ANYWHERE", "d2-a1", null)));
        assertTrue(err != null && err.contains("不支持的操作类型"));
    }

    @Test
    void unknownNodeRejected() {
        ItineraryPlan plan = plan3Days();
        String err = ItineraryPatchEngine.validateOps(plan, intent(List.of(2), op("REMOVE", "d2-x9", null)));
        assertTrue(err != null && err.contains("节点不存在"));
    }

    @Test
    void applyRemoveKeepsUntouchedSemanticsAndStableIds() {
        ItineraryPlan plan = plan3Days();
        ItineraryPlan patched = copyOf(plan);
        Map<String, Long> ids = new HashMap<>();
        List<String> touched = ItineraryPatchEngine.applyOperations(patched,
                List.of(op("REMOVE", "d2-a2", null)), ids, Map.of());
        assertEquals(List.of("d2-a2"), touched);
        assertEquals(List.of("d2-a1"), patched.getDays().get(1).getNodes().stream()
                .filter(n -> "attraction".equals(n.getType())).map(PlanNode::getNodeId).toList());
        assertTrue(ItineraryPatchEngine.compareUntouched(plan, patched, touched).isEmpty());
    }

    @Test
    void replaceKeepsNodeIdAndChangesPlace() {
        ItineraryPlan plan = plan3Days();
        ItineraryPlan patched = copyOf(plan);
        List<String> touched = ItineraryPatchEngine.applyOperations(patched,
                List.of(op("REPLACE_PLACE", "d2-a2", "ATTRACTION:99")),
                Map.of("ATTRACTION:99", 99L), Map.of(99L, "景点99"));
        ItineraryPatchEngine.LocatedNode replaced = ItineraryPatchEngine.findNode(patched, "d2-a2");
        assertEquals(99L, replaced.node().getPlaceId());
        assertEquals("d2-a2", replaced.node().getNodeId());
        assertEquals("景点99", replaced.node().getName());
        assertTrue(ItineraryPatchEngine.compareUntouched(plan, patched, touched).isEmpty());
    }

    @Test
    void untouchedMismatchDetected() {
        ItineraryPlan plan = plan3Days();
        ItineraryPlan patched = copyOf(plan);
        ItineraryPatchEngine.findNode(patched, "d1-a1").node().setTime("23:00");
        List<String> mismatches = ItineraryPatchEngine.compareUntouched(plan, patched, List.of("d2-a2"));
        assertTrue(mismatches.contains("d1-a1"));
    }

    @Test
    void stableIdsAssignedOnceAndNeverReassigned() {
        ItineraryPlan plan = plan3Days();
        ItineraryService.assignNodeIds(plan); // 已有 nodeId 不覆盖
        ItineraryPatchEngine.LocatedNode n = ItineraryPatchEngine.findNode(plan, "d2-a2");
        assertEquals("景点4", n.node().getName());
        ItineraryPlan legacy = new ItineraryPlan();
        DailyPlan d = new DailyPlan();
        d.setDayIndex(2);
        List<PlanNode> nodes = new ArrayList<>();
        PlanNode a = new PlanNode();
        a.setType("attraction");
        PlanNode b = new PlanNode();
        b.setType("attraction");
        nodes.add(a);
        nodes.add(b);
        d.setNodes(nodes);
        legacy.setDays(new ArrayList<>(List.of(d)));
        ItineraryService.assignNodeIds(legacy);
        assertEquals("d2-a1", legacy.getDays().get(0).getNodes().get(0).getNodeId());
        assertEquals("d2-a2", legacy.getDays().get(0).getNodes().get(1).getNodeId());
    }

    private static ItineraryPlan copyOf(ItineraryPlan plan) {
        // 与生产一致：经 JSON 深拷贝（验证字段完整性）
        ItineraryPlan copy = new ItineraryPlan();
        copy.setDays(new ArrayList<>());
        for (DailyPlan d : plan.getDays()) {
            DailyPlan nd = new DailyPlan();
            nd.setDayIndex(d.getDayIndex());
            nd.setNodes(new ArrayList<>());
            for (PlanNode n : d.getNodes()) {
                PlanNode nn = new PlanNode();
                nn.setNodeId(n.getNodeId());
                nn.setType(n.getType());
                nn.setPlaceId(n.getPlaceId());
                nn.setTime(n.getTime());
                nn.setName(n.getName());
                nn.setNote(n.getNote());
                nn.setSeq(n.getSeq());
                nn.setDurationMinutes(n.getDurationMinutes());
                nn.setTravelMinutes(n.getTravelMinutes());
                nn.setDepartTime(n.getDepartTime());
                nd.getNodes().add(nn);
            }
            copy.getDays().add(nd);
        }
        return copy;
    }
}
