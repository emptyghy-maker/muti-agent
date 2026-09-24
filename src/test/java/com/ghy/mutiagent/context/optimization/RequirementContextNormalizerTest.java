package com.ghy.mutiagent.context.optimization;

import com.ghy.mutiagent.model.ConstraintEntry;
import com.ghy.mutiagent.model.RequirementSnapshot;
import com.ghy.mutiagent.model.requirement.RequirementOperator;
import com.ghy.mutiagent.model.requirement.RequirementScope;
import com.ghy.mutiagent.model.requirement.RequirementSubject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RequirementContextNormalizerTest {

    private final RequirementContextNormalizer normalizer = new RequirementContextNormalizer();

    @Test
    void exactSemanticDuplicatesRenderOnceAndKeepSources() {
        RequirementSnapshot snapshot = snapshot(
                entry("R1", "preference", "HARD", RequirementScope.TRIP, 2),
                entry("R2", "extraRequest", "HARD", RequirementScope.TRIP, 2));

        List<NormalizedRequirement> result = normalizer.normalize(snapshot);

        assertEquals(1, result.size());
        assertEquals(List.of("preference", "extraRequest"), result.get(0).sourceFields());
    }

    @Test
    void hardnessAndScopeAreNeverMerged() {
        RequirementSnapshot snapshot = snapshot(
                entry("R1", "a", "HARD", RequirementScope.TRIP, 1),
                entry("R2", "b", "SOFT", RequirementScope.TRIP, 1),
                entry("R3", "c", "HARD", RequirementScope.PER_DAY, 1));

        assertEquals(3, normalizer.normalize(snapshot).size());
    }

    @Test
    void revokedAndSupersededTextDoesNotEnterActiveProjection() {
        ConstraintEntry active = entry("R1", "a", "HARD", RequirementScope.TRIP, 1);
        ConstraintEntry revoked = entry("R2", "b", "HARD", RequirementScope.TRIP, 2);
        revoked.setStatus("REVOKED");
        ConstraintEntry superseded = entry("R3", "c", "HARD", RequirementScope.TRIP, 3);
        superseded.setStatus("SUPERSEDED");

        assertEquals(List.of("R1"), normalizer.normalize(snapshot(active, revoked, superseded)).stream()
                .map(NormalizedRequirement::requirementId).toList());
    }

    private static RequirementSnapshot snapshot(ConstraintEntry... entries) {
        RequirementSnapshot value = new RequirementSnapshot();
        value.setConstraints(List.of(entries));
        return value;
    }

    private static ConstraintEntry entry(String id, String source, String hardness,
                                         RequirementScope scope, int count) {
        ConstraintEntry e = new ConstraintEntry();
        e.setId(id);
        e.setRevision(1);
        e.setStatus("ACTIVE");
        e.setSource(source);
        e.setHardness(hardness);
        e.setSubject(RequirementSubject.LUNCH);
        e.setOperator(RequirementOperator.EQ);
        e.setScope(scope);
        e.setCount(count);
        return e;
    }
}
