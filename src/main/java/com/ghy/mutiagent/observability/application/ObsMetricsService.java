package com.ghy.mutiagent.observability.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ghy.mutiagent.observability.domain.ObsStatuses;
import com.ghy.mutiagent.observability.persistence.ObsRun;
import com.ghy.mutiagent.observability.persistence.ObsRunMapper;
import com.ghy.mutiagent.observability.persistence.ObsSpan;
import com.ghy.mutiagent.observability.persistence.ObsSpanMapper;
import com.ghy.mutiagent.observability.persistence.ObsUsageAttempt;
import com.ghy.mutiagent.observability.persistence.ObsUsageAttemptMapper;
import com.ghy.mutiagent.observability.security.ObsScope;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 观测指标服务（开发文档 §9/指标口径文档）：由后端统一计算，前端只展示。
 * - 完成率：COMMITTED 逻辑操作数 / 逻辑操作数（失败、取消计入分母；在途单列）；
 * - 费用：全部已知费用（含失败重试）为分子；未知单列且 costComplete=false；
 *   成功数为 0 时单位成功成本为 null；
 * - 耗时：墙钟与并行总工作量分开，互不加总。
 * 所有查询携带 Scope；owner=null 隔离域一律不可见。
 */
public final class ObsMetricsService {

    private final ObsRunMapper runMapper;
    private final ObsSpanMapper spanMapper;
    private final ObsUsageAttemptMapper attemptMapper;

    public ObsMetricsService(ObsRunMapper runMapper, ObsSpanMapper spanMapper,
                             ObsUsageAttemptMapper attemptMapper) {
        this.runMapper = runMapper;
        this.spanMapper = spanMapper;
        this.attemptMapper = attemptMapper;
    }

    /** 运行口径：分子分母 + 在途 + 比率 */
    public record RunMetrics(int operationCount, int successCount, int inFlightCount,
                             Double successRate) {
    }

    /** 费用口径：已知合计 + 未知数 + 完整性 + 单位成功成本 */
    public record CostMetrics(BigDecimal totalKnownCost, int successfulPlanCount,
                              BigDecimal costPerSuccessfulPlan, int unknownCostCount,
                              boolean costComplete) {
    }

    /** 耗时口径：墙钟 vs 并行工作量 */
    public record DurationMetrics(Long wallMs, Long providerWorkMs) {
    }

    public RunMetrics runMetrics(ObsScope scope) {
        List<ObsRun> runs = scopedRuns(scope);
        int operationCount = 0;
        int success = 0;
        int inFlight = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ObsRun r : runs) {
            String key = r.getOperationId() != null ? r.getOperationId() : "sess:" + r.getRunId();
            if (!seen.add(key)) {
                continue;
            }
            operationCount++;
            if (ObsStatuses.RUN_RUNNING.equals(r.getRunStatus())) {
                inFlight++;
            }
            if (com.ghy.mutiagent.service.TravelOperationService.STATUS_COMPLETED
                    .equals(r.getBusinessStatus())
                    || "COMMITTED".equals(r.getBusinessStatus())) {
                success++;
            }
        }
        Double rate = operationCount == 0 ? null : (double) success / operationCount;
        return new RunMetrics(operationCount, success, inFlight, rate);
    }

    public CostMetrics costMetrics(ObsScope scope) {
        List<ObsRun> scoped = scopedRuns(scope);
        java.util.Set<String> runIds = new java.util.HashSet<>();
        int successfulPlanCount = 0;
        for (ObsRun r : scoped) {
            runIds.add(r.getRunId());
            if ("COMMITTED".equals(r.getBusinessStatus())
                    || com.ghy.mutiagent.service.TravelOperationService.STATUS_COMPLETED
                            .equals(r.getBusinessStatus())) {
                successfulPlanCount++;
            }
        }
        BigDecimal known = BigDecimal.ZERO;
        int unknown = 0;
        List<ObsUsageAttempt> attempts = runIds.isEmpty() ? List.of()
                : attemptMapper.selectList(new LambdaQueryWrapper<ObsUsageAttempt>()
                        .in(ObsUsageAttempt::getRunId, runIds));
        for (ObsUsageAttempt a : attempts) {
            if (ObsStatuses.AMOUNT_UNKNOWN.equals(a.getAmountStatus())) {
                unknown++;
            } else if (a.getCostAmount() != null) {
                known = known.add(a.getCostAmount());
            }
        }
        BigDecimal unit = successfulPlanCount == 0 ? null
                : known.divide(BigDecimal.valueOf(successfulPlanCount), 6, RoundingMode.HALF_UP);
        return new CostMetrics(known, successfulPlanCount, unit, unknown, unknown == 0);
    }

    public DurationMetrics durations(String runId) {
        ObsRun run = runMapper.selectById(runId);
        Long wall = run == null ? null : run.getDurationMs();
        long work = 0;
        List<ObsSpan> spans = spanMapper.selectList(new LambdaQueryWrapper<ObsSpan>()
                .eq(ObsSpan::getRunId, runId));
        for (ObsSpan s : spans) {
            if (s.getDurationMs() != null) {
                work += s.getDurationMs();
            }
        }
        return new DurationMetrics(wall, work);
    }

    private List<ObsRun> scopedRuns(ObsScope scope) {
        if (scope == null) {
            return List.of();
        }
        LambdaQueryWrapper<ObsRun> q = new LambdaQueryWrapper<>();
        if (!scope.admin()) {
            q.eq(ObsRun::getOwnerId, scope.ownerId());
        } else {
            q.isNotNull(ObsRun::getOwnerId); // 隔离域（owner=null）对 ADMIN 也不可见
        }
        return runMapper.selectList(q);
    }
}
