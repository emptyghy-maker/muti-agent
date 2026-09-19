package com.ghy.mutiagent.service.eval;

import java.util.List;

/**
 * 离线报告装配（手册 §6）：业务与观测分离——观测存储失败不触发业务重跑，
 * 报告必须显式标记不完整（dataComplete=false）并给出 OBSERVABILITY_DATA_GAP 原因。
 * 业务提交数/模型请求数如实上报，不用估算掩盖缺口。
 */
public final class EvalReportAssembler {

    public static final String OBSERVABILITY_DATA_GAP = "OBSERVABILITY_DATA_GAP";

    private EvalReportAssembler() {
    }

    /** 装配结果：业务/请求计数 + 数据完整性 + 原因码 */
    public record AssembledReport(int businessCommitCount, int providerRequestCount,
                                  boolean dataComplete, List<String> reasonCodes) {

        public AssembledReport {
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        }

        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("businessCommitCount", businessCommitCount);
            m.put("providerRequestCount", providerRequestCount);
            m.put("dataComplete", dataComplete);
            m.put("reasonCodes", reasonCodes);
            return m;
        }
    }

    /**
     * 装配报告：traceStoreFailed 时数据不完整并附 OBSERVABILITY_DATA_GAP；
     * 计数由调用方从真实业务与真实请求路径采集后传入（不重算）。
     */
    public static AssembledReport assemble(boolean traceStoreFailed,
                                           int businessCommitCount, int providerRequestCount) {
        boolean complete = !traceStoreFailed;
        List<String> codes = traceStoreFailed ? List.of(OBSERVABILITY_DATA_GAP) : List.of();
        return new AssembledReport(businessCommitCount, providerRequestCount, complete, codes);
    }
}
