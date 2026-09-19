package com.ghy.mutiagent.service;

import com.ghy.mutiagent.service.validation.ItineraryValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S08 违规统一：发布检查结果 → 修复违规（OPENING_HOURS_CONFLICT → OPENING_HOURS_VIOLATION），
 * 节点定位（d2-a1）随违规进入修复输入。
 */
class RepairViolationUnifyTest {

    @Test
    void openingHoursConflictBecomesRepairCodeWithNodes() {
        ItineraryValidator.ValidationResult check = new ItineraryValidator.ValidationResult(
                List.of(ItineraryValidator.OPENING_HOURS_CONFLICT), List.of(), false, false,
                Map.of(ItineraryValidator.OPENING_HOURS_CONFLICT, List.of("d2-a1")));
        List<ItineraryRepairEngine.RepairViolation> vs = ItineraryRepairEngine.unify(check);
        assertEquals(1, vs.size());
        assertEquals("OPENING_HOURS_VIOLATION", vs.get(0).code());
        assertEquals(List.of("d2-a1"), vs.get(0).nodeIds());
        assertEquals(2, vs.get(0).day());
        assertTrue(vs.get(0).repairable());
    }

    @Test
    void structureInvalidNotRepairable() {
        ItineraryValidator.ValidationResult check = new ItineraryValidator.ValidationResult(
                List.of(ItineraryValidator.STRUCTURE_INVALID), List.of(), false, false, Map.of());
        List<ItineraryRepairEngine.RepairViolation> vs = ItineraryRepairEngine.unify(check);
        assertEquals(1, vs.size());
        assertEquals(ItineraryValidator.STRUCTURE_INVALID, vs.get(0).code());
        assertTrue(!vs.get(0).repairable());
    }

    @Test
    void cleanResultUnifiesToNothing() {
        ItineraryValidator.ValidationResult check = ItineraryValidator.ValidationResult.publishable(List.of());
        assertTrue(ItineraryRepairEngine.unify(check).isEmpty());
    }
}
