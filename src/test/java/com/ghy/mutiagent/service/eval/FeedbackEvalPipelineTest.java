package com.ghy.mutiagent.service.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 反馈到离线报告闭环：复核隔离、ID 关联、原始结果、悬空引用检测 */
class FeedbackEvalPipelineTest {

    private static final FeedbackEvalPipeline.RawProvider ECHO =
            (sampleId, feedbackKey, repeat) -> "RAW-" + sampleId + "-" + repeat;

    @Test
    void approvedFeedbackEvaluatedWithFullIdClosure() {
        FeedbackEvalPipeline.EvalReport report = FeedbackEvalPipeline.evaluate(
                List.of(new FeedbackEvalPipeline.ReviewedFeedback("fb-1", 7L, 2, "APPROVED")),
                2, "STUB", "run", ECHO);

        assertEquals(1, report.evaluatedFeedbackCount());
        assertEquals(2, report.rawRunCount());
        assertTrue(report.brokenReferences().isEmpty());
        assertEquals(2, report.rawRuns().size());
        assertEquals("run-fb-1-r1", report.rawRuns().get(1).runId());
        assertEquals("sample-fb-1", report.rawRuns().get(0).sampleId());
        assertEquals("STUB", report.providerMode());
        assertFalse(report.productionQualityClaim());
        assertEquals("STUB", report.toMap().get("providerMode"));
    }

    @Test
    void nonApprovedFeedbackExcludedFromEvaluation() {
        FeedbackEvalPipeline.EvalReport report = FeedbackEvalPipeline.evaluate(
                List.of(new FeedbackEvalPipeline.ReviewedFeedback("fb-1", 7L, 2, "PENDING")),
                2, "STUB", "run", ECHO);
        assertEquals(0, report.evaluatedFeedbackCount());
        assertEquals(0, report.rawRunCount());
    }

    @Test
    void danglingReferencesDetected() {
        List<FeedbackEvalPipeline.EvalRun> runs = List.of(
                new FeedbackEvalPipeline.EvalRun("r1", "sample-fb-1", "fb-1", "STUB", "x", 1L),
                new FeedbackEvalPipeline.EvalRun("r2", "sample-fb-9", "fb-9", "STUB", "x", 2L));
        List<String> broken = FeedbackEvalPipeline.validateReferences(runs, Set.of("fb-1"));
        assertEquals(List.of("r2->feedback:fb-9"), broken);
    }

    @Test
    void rawResultsKeptForEveryRepeat() {
        FeedbackEvalPipeline.EvalReport report = FeedbackEvalPipeline.evaluate(
                List.of(new FeedbackEvalPipeline.ReviewedFeedback("fb-1", 7L, 2, "APPROVED")),
                3, "STUB", "run", ECHO);
        assertEquals("RAW-sample-fb-1-2", report.rawRuns().get(2).rawResult());
        assertEquals(3, report.toMap().get("rawRunCount"));
    }
}
