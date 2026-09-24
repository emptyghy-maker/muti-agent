package com.ghy.mutiagent.service;

import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.model.TravelState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ItineraryPlanningInputTest {

    @Test
    void modelInputContainsOrderingSignalsButExcludesJavaOwnedBudgetFields() {
        TravelPreference preference = new TravelPreference();
        preference.setDays(2);
        preference.setEnergyLevel("一般");
        preference.setReturnDeadline("21:30");
        preference.setTotalBudget(new BigDecimal("600"));
        preference.setPeopleCount(2);
        TravelState state = new TravelState();
        state.setPreference(preference);

        Map<String, Object> input = ItineraryService.planningPreference(state);

        assertEquals(2, input.get("days"));
        assertEquals("一般", input.get("energyLevel"));
        assertEquals("21:30", input.get("returnDeadline"));
        assertFalse(input.containsKey("totalBudget"));
        assertFalse(input.containsKey("peopleCount"));
    }
}
