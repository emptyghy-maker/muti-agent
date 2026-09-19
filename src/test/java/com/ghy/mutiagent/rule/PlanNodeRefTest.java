package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.PlanNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * S08 节点引用：修复输入/违规定位用的确定性寻址（d2-a1 = 第 2 天第 1 个 attraction）。
 */
class PlanNodeRefTest {

    private static PlanNode node(String type) {
        PlanNode n = new PlanNode();
        n.setType(type);
        return n;
    }

    @Test
    void refsCountOrdinalPerTypeWithinDay() {
        List<PlanNode> nodes = List.of(
                node("hotel"), node("attraction"), node("restaurant"),
                node("attraction"), node("transport"), node("attraction"));
        assertEquals(List.of("d2-h1", "d2-a1", "d2-r1", "d2-a2", "d2-t1", "d2-a3"),
                PlanNodeRef.refs(nodes, 2));
    }

    @Test
    void unknownTypeUsesXInitial() {
        assertEquals("d1-x1", PlanNodeRef.ref(1, null, 1));
        assertEquals("d3-s1", PlanNodeRef.ref(3, "rest", 1));
    }

    @Test
    void dayOfParsesPrefix() {
        assertEquals(2, PlanNodeRef.dayOf("d2-a1"));
        assertEquals(0, PlanNodeRef.dayOf("a1"));
        assertEquals(0, PlanNodeRef.dayOf(null));
        assertEquals(0, PlanNodeRef.dayOf("dx-a1"));
    }
}
