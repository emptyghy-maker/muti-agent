package com.ghy.mutiagent.context.turn;

import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.model.TravelState;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 把解释草案转换为可审计的 Before/After 变更说明；第二阶段不负责统一失效。 */
@Component
public class TurnChangePlanner {

    public ContextChangeSet plan(TurnContext turn, TravelState state, TurnInterpretation interpretation) {
        ContextChangeSet changes = new ContextChangeSet();
        TravelPreference preference = state == null ? null : state.getPreference();
        interpretation.getPreferenceUpdates().forEach((field, value) -> {
            Object oldValue = currentValue(preference, field);
            if (!Objects.equals(String.valueOf(oldValue), value)) {
                changes.getOldValues().put(field, oldValue);
                changes.getNewValues().put(field, value);
                changes.setInvalidatePlan(true);
                affected(field, changes);
            }
        });
        if (!interpretation.getRequirementChanges().isEmpty()) {
            changes.setRequirementRevisionChanged(true);
            changes.setConstraintRevisionChanged(true);
            changes.setInvalidatePlan(true);
        }
        changes.setNeedsClarification(interpretation.isNeedsClarification());
        if (turn != null) turn.setChangeSet(changes);
        return changes;
    }

    private static Object currentValue(TravelPreference p, String field) {
        if (p == null) return null;
        return switch (field) {
            case "days" -> p.getDays();
            case "totalBudget" -> p.getTotalBudget();
            case "peopleCount" -> p.getPeopleCount();
            case "attractionType" -> p.getAttractionType();
            case "foodTaste" -> p.getFoodTaste();
            case "energyLevel" -> p.getEnergyLevel();
            case "hotelStyle" -> p.getHotelStyle();
            case "specialRequests" -> p.getSpecialRequests();
            default -> null;
        };
    }

    private static void affected(String field, ContextChangeSet changes) {
        switch (field) {
            case "foodTaste" -> changes.getAffectedChannels().add("FOOD");
            case "hotelStyle" -> changes.getAffectedChannels().add("HOTEL");
            case "attractionType", "energyLevel" -> changes.getAffectedChannels().add("ATTRACTION");
            default -> {
                changes.getAffectedChannels().add("ATTRACTION");
                changes.getAffectedChannels().add("FOOD");
                changes.getAffectedChannels().add("HOTEL");
            }
        }
    }
}
