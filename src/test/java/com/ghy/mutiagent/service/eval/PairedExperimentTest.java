package com.ghy.mutiagent.service.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 成对对照实验：每臂计数、配对完整性、原始结果保存、工具事实一致、口径声明 */
class PairedExperimentTest {

    private static DatasetSample sample(String id, String input) {
        return new DatasetSample(id, "g-" + id, "类别", "development", input,
                Map.of(), Map.of(), Map.of("hardViolationCount", 0),
                "fixture-1", "p-v1", "stub-v1", "synthetic");
    }

    private static PairedExperiment.RawProvider echoProvider() {
        return (version, sampleId, input, repeat) -> "RAW:" + version + ":" + sampleId + "#" + repeat;
    }

    @Test
    void pairedRunCountsAndSymmetry() {
        List<DatasetSample> samples = List.of(sample("ordinary", "杭州两天"), sample("budget", "预算300"));
        PairedExperiment.ExperimentReport report = PairedExperiment.run(
                samples, 3, "baseline-v1", "candidate-v2", "hash-tools-1", "STUB", echoProvider());

        assertEquals(6, report.baselineRunCount());
        assertEquals(6, report.candidateRunCount());
        assertEquals(12, report.totalRunCount());
        assertEquals(12, report.rawResults().size());
        assertTrue(report.unpairedKeys().isEmpty());
        assertTrue(report.missingRawResults().isEmpty());
    }

    @Test
    void toolSnapshotHashesEqualAcrossArms() {
        List<DatasetSample> samples = List.of(sample("ordinary", "杭州两天"));
        PairedExperiment.ExperimentReport report = PairedExperiment.run(
                samples, 3, "baseline-v1", "candidate-v2", "hash-tools-1", "STUB", echoProvider());

        assertEquals(List.of("hash-tools-1", "hash-tools-1", "hash-tools-1"),
                report.toolSnapshotHashesFor("baseline-v1"));
        assertEquals(report.toolSnapshotHashesFor("baseline-v1"),
                report.toolSnapshotHashesFor("candidate-v2"));
    }

    @Test
    void stubModeNeverClaimsProductionQuality() {
        List<DatasetSample> samples = List.of(sample("ordinary", "杭州两天"));
        PairedExperiment.ExperimentReport report = PairedExperiment.run(
                samples, 1, "baseline-v1", "candidate-v2", "h", "STUB", echoProvider());
        assertEquals("STUB", report.providerMode());
        assertFalse(report.productionQualityClaim());
        assertEquals("STUB", report.toMap().get("providerMode"));
        assertEquals(false, report.toMap().get("productionQualityClaim"));
    }

    @Test
    void liveModeMarksProductionQualityClaim() {
        PairedExperiment.ExperimentReport report = PairedExperiment.run(
                List.of(sample("ordinary", "杭州两天")), 1, "b", "c", "h", "LIVE", echoProvider());
        assertTrue(report.productionQualityClaim());
    }

    @Test
    void missingRawResultsReported() {
        List<DatasetSample> samples = List.of(sample("ordinary", "杭州两天"), sample("budget", "预算300"));
        PairedExperiment.RawProvider provider = (version, sampleId, input, repeat) ->
                sampleId.equals("budget") && repeat == 1 ? "" : "RAW";
        PairedExperiment.ExperimentReport report = PairedExperiment.run(
                samples, 3, "baseline-v1", "candidate-v2", "h", "STUB", provider);

        assertEquals(2, report.missingRawResults().size());
        assertTrue(report.missingRawResults().contains("baseline-v1/budget#1"));
        assertTrue(report.missingRawResults().contains("candidate-v2/budget#1"));
    }

    @Test
    void rawResultsAreKeptNotOnlySummaries() {
        List<DatasetSample> samples = List.of(sample("ordinary", "杭州两天"));
        PairedExperiment.ExperimentReport report = PairedExperiment.run(
                samples, 2, "baseline-v1", "candidate-v2", "h", "STUB", echoProvider());
        // AB/BA 轮换：repeat0 [baseline, candidate]，repeat1 [candidate, baseline]
        assertEquals("RAW:candidate-v2:ordinary#1", report.rawResults().get(2).rawResult());
    }
}
