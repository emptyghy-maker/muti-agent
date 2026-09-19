package com.ghy.mutiagent.service.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 报告装配：观测故障显式不完整并附原因码，计数如实上报 */
class EvalReportAssemblerTest {

    @Test
    void traceStoreFailureMarksIncompleteWithGapCode() {
        EvalReportAssembler.AssembledReport report =
                EvalReportAssembler.assemble(true, 1, 1);
        assertFalse(report.dataComplete());
        assertTrue(report.reasonCodes().contains(EvalReportAssembler.OBSERVABILITY_DATA_GAP));
        assertEquals(1, report.businessCommitCount());
        assertEquals(1, report.providerRequestCount());
        assertEquals(false, report.toMap().get("dataComplete"));
        assertTrue(((java.util.List<?>) report.toMap().get("reasonCodes"))
                .contains(EvalReportAssembler.OBSERVABILITY_DATA_GAP));
    }

    @Test
    void healthyObservabilityMarksCompleteWithoutCodes() {
        EvalReportAssembler.AssembledReport report =
                EvalReportAssembler.assemble(false, 1, 1);
        assertTrue(report.dataComplete());
        assertTrue(report.reasonCodes().isEmpty());
    }
}
