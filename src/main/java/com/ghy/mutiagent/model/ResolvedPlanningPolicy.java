package com.ghy.mutiagent.model;

import com.ghy.mutiagent.model.requirement.RequirementOperator;
import com.ghy.mutiagent.model.requirement.RequirementScope;
import com.ghy.mutiagent.model.requirement.RequirementSubject;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * O2：由一个确定版本的 RequirementSnapshot 生成的统一执行策略。
 * 候选、规划 Prompt、确定性后处理、Repair 与 Validator 必须消费同一版本。
 */
@Data
public class ResolvedPlanningPolicy {
    public static final String VERSION = "meal-policy-v2";

    private String policyVersion = VERSION;
    private int requirementSnapshotRevision;
    private MealPolicy meal = new MealPolicy();
    private List<String> blockingRequirementIds = new ArrayList<>();
    private List<String> conflictCodes = new ArrayList<>();

    public boolean executable() {
        return blockingRequirementIds.isEmpty() && conflictCodes.isEmpty();
    }

    @Data
    public static class MealPolicy {
        private MealRule lunch;
        private MealRule dinner;
        /** 用户明确要求的小吃数量。 */
        private MealRule snack;
        /** 用户明确要求的正餐数量。 */
        private MealRule mainMeal;
        /** true 时午餐/晚餐只表示时间窗，小吃可以占用其中一个时间窗。 */
        private boolean explicitMealComposition;
        private Integer lunchCandidateCount;
        private Integer dinnerCandidateCount;
        private boolean snacksAllowed = true;
        private String snackRequirementId;
        private int snackRequirementRevision;
        private String snackHardness;
        private boolean noFood;
        private boolean restaurantReuseAllowed;
    }

    @Data
    public static class MealRule {
        private RequirementSubject subject;
        private RequirementOperator operator = RequirementOperator.EQ;
        private int count;
        private RequirementScope scope;
        private boolean explicit;
        private String requirementId;
        private int requirementRevision;
        private String hardness;

        public static MealRule defaultPerDay(RequirementSubject subject) {
            MealRule r = new MealRule();
            r.setSubject(subject);
            r.setCount(1);
            r.setScope(RequirementScope.PER_DAY);
            r.setExplicit(false);
            r.setRequirementId("DEFAULT-" + subject.name());
            r.setRequirementRevision(1);
            r.setHardness("HARD");
            return r;
        }
    }
}
