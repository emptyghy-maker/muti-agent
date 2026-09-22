package com.ghy.mutiagent.model;

import com.ghy.mutiagent.model.requirement.FulfillmentStatus;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** O2：一个计划版本相对于一个需求快照版本的逐需求验收报告。 */
@Data
public class RequirementFulfillmentReport {
    public static final String VALIDATOR_VERSION = "meal-validator-v1";

    private String reportId;
    private String validatorVersion = VALIDATOR_VERSION;
    private int requirementSnapshotRevision;
    private int planRevision;
    private List<RequirementFulfillmentResult> results = new ArrayList<>();

    public boolean hardRequirementsSatisfied() {
        return results.stream()
                .filter(r -> "HARD".equals(r.getHardness()))
                .allMatch(r -> r.getStatus() == FulfillmentStatus.SATISFIED);
    }

    /** 保留旧证据供追溯，但明确禁止把它当成当前版本的验收结论。 */
    public void markStale() {
        results.forEach(r -> r.setStatus(FulfillmentStatus.STALE));
    }

    public boolean matches(int snapshotRevision, int currentPlanRevision) {
        return requirementSnapshotRevision == snapshotRevision && planRevision == currentPlanRevision
                && results.stream().noneMatch(r -> r.getStatus() == FulfillmentStatus.STALE);
    }
}
