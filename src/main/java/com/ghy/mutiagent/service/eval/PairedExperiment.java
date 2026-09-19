package com.ghy.mutiagent.service.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.TreeSet;

/**
 * 成对对照实验执行器（手册 §5.1 / O1.4）：每个 sampleId + repeatIndex 在 baseline/candidate
 * 两臂各执行一次，固定数据、工具事实与判据；保存每次运行的原始结果与工具事实哈希。
 *
 * - 所有运行（包括失败和缺失）都落记录，不静默重试后只保留成功版本；
 * - 每条样本两臂各自独立 try/catch：baseline 失败也保存结果并继续 candidate 与后续样本；
 * - AB/BA 轮换：repeatIndex 为偶数先 baseline 后 candidate，奇数反过来，执行顺序随 sequenceIndex 可追溯；
 * - 两臂工具事实快照必须一致，逐臂上报哈希供比对；
 * - 报告显式标注 providerMode（STUB/LIVE），STUB 通过只说明业务管道与门禁符合设计，
 *   不构成模型质量结论（productionQualityClaim 仅真实 LIVE 为 true）；
 * - 可选接入 ExperimentRecorder.Sink：逐行写出 runs/attempts（O1.2 任务可展开到物理 attempt）。
 */
public final class PairedExperiment {

    /** 单次运行状态：SUCCESS / PROVIDER_FAILED（provider 抛异常，原始结果缺失） */
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_PROVIDER_FAILED = "PROVIDER_FAILED";

    private PairedExperiment() {
    }

    /** 单次运行记录：版本 + 样本 + 重复序 + 原始结果 + 工具事实哈希 + 时刻 + 状态 + 顺序号 + 模式 */
    public record RunRecord(String version, String sampleId, int repeatIndex,
                            String rawResult, String toolSnapshotHash, long atMs,
                            String status, String error, int sequenceIndex, String providerMode) {
    }

    /** 实验报告：计数 + 全部原始结果 + 配对缺失 + 结果缺失 + 口径声明 */
    public record ExperimentReport(String providerMode, boolean productionQualityClaim,
                                   int totalRunCount, int baselineRunCount, int candidateRunCount,
                                   List<RunRecord> rawResults, List<String> unpairedKeys,
                                   List<String> missingRawResults, String toolSnapshotHash) {

        public ExperimentReport {
            rawResults = rawResults == null ? List.of() : List.copyOf(rawResults);
            unpairedKeys = unpairedKeys == null ? List.of() : List.copyOf(unpairedKeys);
            missingRawResults = missingRawResults == null ? List.of() : List.copyOf(missingRawResults);
        }

        public List<String> toolSnapshotHashesFor(String version) {
            List<String> hashes = new ArrayList<>();
            for (RunRecord r : rawResults) {
                if (version.equals(r.version())) {
                    hashes.add(r.toolSnapshotHash());
                }
            }
            hashes.sort(String::compareTo);
            return hashes;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("providerMode", providerMode);
            m.put("productionQualityClaim", productionQualityClaim);
            m.put("totalRunCount", totalRunCount);
            m.put("baselineRunCount", baselineRunCount);
            m.put("candidateRunCount", candidateRunCount);
            m.put("rawResultCount", rawResults.size());
            m.put("unpairedKeys", unpairedKeys);
            m.put("missingRawResults", missingRawResults);
            m.put("toolSnapshotHash", toolSnapshotHash);
            return m;
        }
    }

    /** 版本替身：返回该版本对某样本某次重复的原始输出（STUB/LIVE 由调用方决定） */
    @FunctionalInterface
    public interface RawProvider {
        String produce(String version, String sampleId, String input, int repeatIndex);
    }

    /** 执行两臂各 (样本数 × 重复数) 次；key = sampleId#repeatIndex（无记录器入口） */
    public static ExperimentReport run(List<DatasetSample> samples, int repeats,
                                       String baselineVersion, String candidateVersion,
                                       String toolSnapshotHash, String providerMode,
                                       RawProvider provider) {
        return run(samples, repeats, baselineVersion, candidateVersion, toolSnapshotHash,
                providerMode, provider, null, null);
    }

    /** 执行两臂各 (样本数 × 重复数) 次；sink 非空时逐行写出 runs/attempts（experimentId 用于关联） */
    public static ExperimentReport run(List<DatasetSample> samples, int repeats,
                                       String baselineVersion, String candidateVersion,
                                       String toolSnapshotHash, String providerMode,
                                       RawProvider provider, ExperimentRecorder.Sink sink,
                                       String experimentId) {
        List<RunRecord> records = new ArrayList<>();
        Set<String> baselineKeys = new LinkedHashSet<>();
        Set<String> candidateKeys = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        int baselineCount = 0;
        int candidateCount = 0;
        int sequence = 0;

        for (DatasetSample sample : samples) {
            for (int r = 0; r < repeats; r++) {
                String key = sample.id() + "#" + r;
                // AB/BA 轮换：偶数 repeat 先 baseline 后 candidate，奇数反过来
                boolean baselineFirst = (r % 2 == 0);
                String first = baselineFirst ? baselineVersion : candidateVersion;
                String second = baselineFirst ? candidateVersion : baselineVersion;
                RunRecord firstRec = record(first, sample, r, toolSnapshotHash, providerMode,
                        provider, sequence++);
                RunRecord secondRec = record(second, sample, r, toolSnapshotHash, providerMode,
                        provider, sequence++);
                records.add(firstRec);
                records.add(secondRec);
                baselineKeys.add(key);
                candidateKeys.add(key);
                if (isBlank(firstRec.rawResult())) {
                    missing.add(firstRec.version() + "/" + key);
                }
                if (isBlank(secondRec.rawResult())) {
                    missing.add(secondRec.version() + "/" + key);
                }
                if (baselineVersion.equals(firstRec.version())) {
                    baselineCount++;
                }
                if (candidateVersion.equals(firstRec.version())) {
                    candidateCount++;
                }
                if (baselineVersion.equals(secondRec.version())) {
                    baselineCount++;
                }
                if (candidateVersion.equals(secondRec.version())) {
                    candidateCount++;
                }
                writeRecords(sink, experimentId, sample, r, firstRec, secondRec, toolSnapshotHash);
            }
        }

        Set<String> unpaired = new TreeSet<>();
        for (String k : baselineKeys) {
            if (!candidateKeys.contains(k)) {
                unpaired.add(k);
            }
        }
        for (String k : candidateKeys) {
            if (!baselineKeys.contains(k)) {
                unpaired.add(k);
            }
        }
        missing.sort(Comparator.naturalOrder());

        return new ExperimentReport(providerMode, "LIVE".equals(providerMode),
                records.size(), baselineCount, candidateCount,
                records, List.copyOf(unpaired), missing, toolSnapshotHash);
    }

    /** 单臂单次执行：provider 抛异常不中断整场实验，记录 PROVIDER_FAILED 行并继续 */
    private static RunRecord record(String version, DatasetSample sample, int repeat,
                                    String toolSnapshotHash, String providerMode,
                                    RawProvider provider, int sequence) {
        try {
            String raw = provider.produce(version, sample.id(), sample.input(), repeat);
            return new RunRecord(version, sample.id(), repeat, raw, toolSnapshotHash,
                    System.currentTimeMillis(), STATUS_SUCCESS, null, sequence, providerMode);
        } catch (Exception e) {
            return new RunRecord(version, sample.id(), repeat, null, toolSnapshotHash,
                    System.currentTimeMillis(), STATUS_PROVIDER_FAILED,
                    e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()),
                    sequence, providerMode);
        }
    }

    /** O1.2：把两臂运行写进记录器——run 行（operation/run 级）与 attempt 行（物理调用级，失败也有行） */
    private static void writeRecords(ExperimentRecorder.Sink sink, String experimentId,
                                     DatasetSample sample, int repeat,
                                     RunRecord first, RunRecord second, String toolSnapshotHash) {
        if (sink == null) {
            return;
        }
        for (RunRecord rec : List.of(first, second)) {
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("experimentId", experimentId);
            run.put("logicalTaskId", sample.id());
            run.put("operationId", sample.id() + "#" + repeat);
            run.put("sampleId", sample.id());
            run.put("repeatIndex", repeat);
            run.put("arm", rec.version());
            run.put("providerMode", rec.providerMode());
            run.put("promptVersion", rec.version());
            run.put("toolSnapshotHash", toolSnapshotHash);
            run.put("status", rec.status());
            run.put("error", rec.error());
            run.put("sequenceIndex", rec.sequenceIndex());
            run.put("atMs", rec.atMs());
            run.put("hasRawResult", !isBlank(rec.rawResult()));
            sink.writeRun(run);

            Map<String, Object> attempt = new LinkedHashMap<>();
            attempt.put("experimentId", experimentId);
            attempt.put("logicalTaskId", sample.id());
            attempt.put("operationId", sample.id() + "#" + repeat);
            attempt.put("attemptId", rec.version() + "-" + sample.id() + "#" + repeat);
            attempt.put("sampleId", sample.id());
            attempt.put("repeatIndex", repeat);
            attempt.put("arm", rec.version());
            attempt.put("providerMode", rec.providerMode());
            attempt.put("promptHash", rec.version());
            attempt.put("providerStatus", rec.status());
            attempt.put("parseStatus", null);
            attempt.put("validationStatus", null);
            attempt.put("error", rec.error());
            attempt.put("atMs", rec.atMs());
            attempt.put("evidenceRef", "run/experimentId=" + experimentId + "&sequence=" + rec.sequenceIndex());
            sink.writeAttempt(attempt);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
