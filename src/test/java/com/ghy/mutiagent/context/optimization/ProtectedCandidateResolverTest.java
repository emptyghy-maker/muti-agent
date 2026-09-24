package com.ghy.mutiagent.context.optimization;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProtectedCandidateResolverTest {

    @Test
    void protectsLockedSelectedAndUniqueHardMatchFromDeterministicEvidence() {
        ProtectedCandidateResolver.Resolution result = new ProtectedCandidateResolver().resolve(
                Set.of("ATTRACTION:1"), Set.of("ATTRACTION:2"),
                Map.of("R-accessible", Set.of("ATTRACTION:15"),
                        "R-indoor", Set.of("ATTRACTION:3", "ATTRACTION:4")));

        assertEquals(Set.of("ATTRACTION:1", "ATTRACTION:2", "ATTRACTION:15"), result.protectedKeys());
        assertTrue(result.reasonCodes().get("ATTRACTION:15").get(0).contains("R-accessible"));
        assertFalse(result.protectedKeys().contains("ATTRACTION:3"));
    }
}
