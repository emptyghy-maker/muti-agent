package com.ghy.mutiagent.observability.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ghy.mutiagent.observability.domain.ObsStatuses;
import com.ghy.mutiagent.repository.entity.UsageRecord;
import com.ghy.mutiagent.repository.mapper.UsageRecordMapper;
import com.ghy.mutiagent.service.ModelPricing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 旧用量记录适配（开发文档 §9）：t_usage_record 只读投影为 LEGACY 尝试，
 * 价格版本缺失按当前价估算并标 LEGACY_ESTIMATE；与 NEW 分区展示，不合并成完整统计。
 * 不修改 UsageService 原 costOf 算法；禁止用时间邻近猜测去重。
 */
public final class UsageLegacyAdapter {

    private final UsageRecordMapper usageMapper;
    private final ModelPricing modelPricing;

    public UsageLegacyAdapter(UsageRecordMapper usageMapper, ModelPricing modelPricing) {
        this.usageMapper = usageMapper;
        this.modelPricing = modelPricing;
    }

    /** 会话的旧用量投影（source=LEGACY；金额 LEGACY_ESTIMATE；coverage 由调用方标注） */
    public List<Map<String, Object>> legacyAttempts(String sessionId, int limit) {
        List<UsageRecord> rows = usageMapper.selectList(new LambdaQueryWrapper<UsageRecord>()
                .eq(UsageRecord::getSessionId, sessionId)
                .orderByDesc(UsageRecord::getId)
                .last("LIMIT " + Math.max(1, Math.min(200, limit))));
        List<Map<String, Object>> out = new ArrayList<>();
        for (UsageRecord r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sourceSystem", ObsStatuses.SOURCE_LEGACY);
            m.put("agent", r.getAgent());
            m.put("model", r.getModel());
            m.put("inputTokens", r.getInputTokens());
            m.put("outputTokens", r.getOutputTokens());
            m.put("usageStatus", usageStatusOf(r));
            m.put("durationMs", r.getDurationMs());
            m.put("status", r.getStatus());
            m.put("amountStatus", ObsStatuses.AMOUNT_LEGACY_ESTIMATE);
            m.put("currency", "CNY");
            m.put("costAmount", estimate(r));
            m.put("stage", r.getStage());
            m.put("action", r.getAction());
            out.add(m);
        }
        return out;
    }

    private BigDecimal estimate(UsageRecord r) {
        if (modelPricing == null || r.getModel() == null
                || (r.getInputTokens() == null && r.getOutputTokens() == null)) {
            return null;
        }
        long in = r.getInputTokens() == null ? 0 : r.getInputTokens();
        long out = r.getOutputTokens() == null ? 0 : r.getOutputTokens();
        return modelPricing.estimate(r.getModel(), in, out);
    }

    private static String usageStatusOf(UsageRecord r) {
        if (r.getInputTokens() == null && r.getOutputTokens() == null) {
            return ObsStatuses.USAGE_UNKNOWN;
        }
        return ObsStatuses.USAGE_KNOWN;
    }
}
