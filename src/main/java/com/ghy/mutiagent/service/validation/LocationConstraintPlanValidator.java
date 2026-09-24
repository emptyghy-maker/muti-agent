package com.ghy.mutiagent.service.validation;

import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.LocationConstraint;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.repository.entity.Attraction;
import com.ghy.mutiagent.repository.entity.Hotel;
import com.ghy.mutiagent.repository.entity.Restaurant;
import com.ghy.mutiagent.rule.LocationConstraintSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 发布前复验计划中的 POI 是否仍满足“某地附近”约束。 */
public final class LocationConstraintPlanValidator {

    public static final String VIOLATED = "LOCATION_CONSTRAINT_VIOLATED";
    public static final String UNVERIFIABLE = "LOCATION_CONSTRAINT_UNVERIFIABLE";

    public record Result(List<String> violations, List<String> unverifiable) {
        public boolean passed() {
            return violations.isEmpty() && unverifiable.isEmpty();
        }
    }

    private LocationConstraintPlanValidator() {
    }

    public static Result validate(ItineraryPlan plan, LocationConstraint constraint,
                                  Map<Long, Attraction> attractions,
                                  Map<Long, Restaurant> restaurants,
                                  Map<Long, Hotel> hotels) {
        List<String> violations = new ArrayList<>();
        List<String> unverifiable = new ArrayList<>();
        if (plan == null || plan.getDays() == null || constraint == null) {
            return new Result(violations, unverifiable);
        }
        if (!LocationConstraint.RESOLVED.equals(constraint.getStatus())) {
            unverifiable.add("anchor:" + constraint.getAnchorName());
            return new Result(violations, unverifiable);
        }
        for (DailyPlan day : plan.getDays()) {
            if (day == null || day.getNodes() == null) continue;
            for (int i = 0; i < day.getNodes().size(); i++) {
                PlanNode node = day.getNodes().get(i);
                if (node == null || node.getPlaceId() == null) continue;
                String ref = "d" + day.getDayIndex() + "-n" + (i + 1);
                switch (node.getType() == null ? "" : node.getType()) {
                    case "attraction" -> checkAttraction(constraint, node.getPlaceId(), ref,
                            attractions, violations, unverifiable);
                    case "restaurant" -> checkRestaurant(constraint, node.getPlaceId(), ref,
                            restaurants, violations, unverifiable);
                    case "hotel" -> checkHotel(constraint, node.getPlaceId(), ref,
                            hotels, violations, unverifiable);
                    default -> { }
                }
            }
        }
        return new Result(violations, unverifiable);
    }

    private static void checkAttraction(LocationConstraint c, long id, String ref,
                                        Map<Long, Attraction> facts, List<String> bad, List<String> unknown) {
        if (!LocationConstraintSupport.appliesTo(c, LocationConstraintSupport.ATTRACTION)) return;
        Attraction a = facts == null ? null : facts.get(id);
        if (a == null) {
            unknown.add(ref);
        } else if (!LocationConstraintSupport.matches(c, LocationConstraintSupport.ATTRACTION,
                a.getName(), a.getAddress(), a.getLng(), a.getLat())) {
            bad.add(ref);
        }
    }

    private static void checkRestaurant(LocationConstraint c, long id, String ref,
                                        Map<Long, Restaurant> facts, List<String> bad, List<String> unknown) {
        if (!LocationConstraintSupport.appliesTo(c, LocationConstraintSupport.FOOD)) return;
        Restaurant r = facts == null ? null : facts.get(id);
        if (r == null) {
            unknown.add(ref);
        } else if (!LocationConstraintSupport.matches(c, LocationConstraintSupport.FOOD,
                r.getName(), r.getAddress(), r.getLng(), r.getLat())) {
            bad.add(ref);
        }
    }

    private static void checkHotel(LocationConstraint c, long id, String ref,
                                   Map<Long, Hotel> facts, List<String> bad, List<String> unknown) {
        if (!LocationConstraintSupport.appliesTo(c, LocationConstraintSupport.HOTEL)) return;
        Hotel h = facts == null ? null : facts.get(id);
        if (h == null) {
            unknown.add(ref);
        } else if (!LocationConstraintSupport.matches(c, LocationConstraintSupport.HOTEL,
                h.getName(), h.getAddress(), h.getLng(), h.getLat())) {
            bad.add(ref);
        }
    }
}
