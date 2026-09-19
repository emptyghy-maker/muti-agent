package com.ghy.mutiagent.service.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O1 管道烟测：真实数据集加载 → 校验 → 成对实验（STUB，含一臂抛异常）→ JSONL 记录落盘。
 * 验证「一条任务能展开到所有物理 attempt 的记录」与「失败也有一行」的整条链路，不产生真实模型调用。
 */
class O1HarnessSmokeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmpDir;

    @Test
    void stubPipelineWritesRunsAndAttemptsEvenOnProviderFailure() throws Exception {
        URL res = getClass().getClassLoader().getResource("eval/dataset-seed-mini.json");
        assertTrue(res != null, "种子数据集缺失");
        Path seed = Path.of(res.toURI());
        DatasetFile file = DatasetLoader.load(seed);
        assertTrue(DatasetValidator.validate(file).isEmpty(), "种子集应通过契约校验");

        int samples = file.samples().size();
        int repeats = 1;
        ExperimentRecorder.Sink sink = ExperimentRecorder.fileSink(tmpDir);

        PairedExperiment.ExperimentReport report = PairedExperiment.run(
                file.samples(), repeats, "baseline-v1", "candidate-v2",
                file.toolSnapshot() == null ? null : file.toolSnapshot().id(),
                "STUB",
                (version, sampleId, input, repeat) -> {
                    if ("baseline-v1".equals(version) && "ordinary".equals(sampleId)) {
                        throw new IllegalStateException("模拟 provider 故障");
                    }
                    return "RAW:" + version + ":" + sampleId;
                },
                sink, "EXP-SMOKE-001");

        int expectedRuns = samples * repeats * 2;
        assertEquals(expectedRuns, report.totalRunCount());
        assertTrue(report.missingRawResults().contains("baseline-v1/ordinary#0"),
                "provider 失败必须进入缺失记录");

        List<JsonNode> runs = readJsonl(tmpDir.resolve("runs.jsonl"));
        List<JsonNode> attempts = readJsonl(tmpDir.resolve("attempts.jsonl"));
        assertEquals(expectedRuns, runs.size());
        assertEquals(expectedRuns, attempts.size());

        boolean failedRowPresent = false;
        for (JsonNode r : runs) {
            assertEquals("EXP-SMOKE-001", r.path("experimentId").asText());
            assertEquals("STUB", r.path("providerMode").asText()); // STUB 行不允许伪装 LIVE
            if (PairedExperiment.STATUS_PROVIDER_FAILED.equals(r.path("status").asText())) {
                failedRowPresent = true;
                assertTrue(r.path("error").asText().contains("模拟 provider 故障"));
            }
        }
        assertTrue(failedRowPresent, "失败 run 行必须存在");
        for (JsonNode a : attempts) {
            assertEquals("STUB", a.path("providerMode").asText());
            assertTrue(!a.path("attemptId").asText().isBlank());
        }
    }

    private static List<JsonNode> readJsonl(Path file) throws Exception {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(line -> {
                    try {
                        return JSON.readTree(line);
                    } catch (Exception e) {
                        throw new RuntimeException("JSONL 行解析失败: " + line, e);
                    }
                })
                .toList();
    }
}
