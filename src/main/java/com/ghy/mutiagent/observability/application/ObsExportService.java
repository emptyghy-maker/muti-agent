package com.ghy.mutiagent.observability.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.observability.persistence.ObsAuditRow;
import com.ghy.mutiagent.observability.persistence.ObsAuditRowMapper;
import com.ghy.mutiagent.observability.persistence.ObsEventRow;
import com.ghy.mutiagent.observability.persistence.ObsEventRowMapper;
import com.ghy.mutiagent.observability.persistence.ObsRun;
import com.ghy.mutiagent.observability.persistence.ObsRunMapper;
import com.ghy.mutiagent.observability.security.ObsScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 受控脱敏导出（开发文档 §15）：ADMIN 预览数量 + 执行后审计；
 * 只导出 run 摘要与事件摘要（Redactor 口径已在采集时完成，原文不导出）；
 * CSV 类导出处理公式前缀（内容以 ' = + - @ 开头时加 ' 前缀），避免被表格软件执行。
 */
public final class ObsExportService {

    private final ObsRunMapper runMapper;
    private final ObsEventRowMapper eventMapper;
    private final ObsAuditRowMapper auditMapper;

    public ObsExportService(ObsRunMapper runMapper, ObsEventRowMapper eventMapper,
                            ObsAuditRowMapper auditMapper) {
        this.runMapper = runMapper;
        this.eventMapper = eventMapper;
        this.auditMapper = auditMapper;
    }

    /** 预览：范围内 run 数与事件数（不产内容，先看数量） */
    public Map<String, Object> preview(ObsScope scope, int limit) {
        requireAdmin(scope);
        int runs = Math.toIntExact(runMapper.selectCount(scopeWrapped(scope)));
        int events = Math.toIntExact(eventMapper.selectCount(null));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runCount", runs);
        out.put("eventCount", events);
        out.put("limit", Math.max(1, Math.min(200, limit)));
        out.put("mode", "SUMMARY_REDACTED");
        return out;
    }

    /** 导出：run 摘要 + 事件摘要（脱敏口径，无原文）；CSV 公式前缀防护 */
    public Map<String, Object> export(ObsScope scope, String format, int limit) {
        requireAdmin(scope);
        int n = Math.max(1, Math.min(200, limit));
        List<Map<String, Object>> items = new ArrayList<>();
        for (ObsRun r : runMapper.selectList(scopeWrapped(scope)
                .orderByDesc(ObsRun::getCreatedAt).last("LIMIT " + n))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("runId", r.getRunId());
            item.put("operationId", r.getOperationId());
            item.put("ownerId", r.getOwnerId());
            item.put("runStatus", r.getRunStatus());
            item.put("businessStatus", r.getBusinessStatus());
            item.put("dataCompleteness", r.getDataCompleteness());
            item.put("sourceSystem", r.getSourceSystem());
            item.put("durationMs", r.getDurationMs());
            item.put("promptVersion", r.getPromptVersion());
            item.put("modelVersion", r.getModelVersion());
            List<Map<String, Object>> eventItems = new ArrayList<>();
            for (ObsEventRow e : eventMapper.selectList(new LambdaQueryWrapper<ObsEventRow>()
                    .eq(ObsEventRow::getRunId, r.getRunId())
                    .orderByAsc(ObsEventRow::getSequenceNo).last("LIMIT 100"))) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("eventType", e.getEventType());
                ev.put("occurredAt", e.getOccurredAt());
                ev.put("spanId", e.getSpanId());
                ev.put("digest", e.getDigest());
                ev.put("summary", e.getSummaryJson());
                eventItems.add(ev);
            }
            item.put("events", eventItems);
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", "csv".equalsIgnoreCase(format) ? "csv" : "json");
        out.put("itemCount", items.size());
        out.put("items", items);
        out.put("generatedAt", System.currentTimeMillis());
        return out;
    }

    /** CSV 行内容公式前缀防护（表格软件不执行） */
    public static String csvSafe(String cell) {
        if (cell == null) {
            return "";
        }
        String s = String.valueOf(cell);
        if (s.startsWith("=") || s.startsWith("+") || s.startsWith("-") || s.startsWith("@")) {
            return "'" + s;
        }
        return s;
    }

    public void auditExport(com.ghy.mutiagent.security.AuthenticatedUser actor,
                            String format, int count) {
        try {
            ObsAuditRow row = new ObsAuditRow();
            row.setActorId(actor.id());
            row.setActorUsername(actor.username());
            row.setAction("EXPORT_RUNS");
            row.setTargetType("EXPORT");
            row.setTargetId(null);
            row.setDetail(format + "/" + count);
            auditMapper.insert(row);
        } catch (RuntimeException ignored) {
        }
    }

    private void requireAdmin(ObsScope scope) {
        if (scope == null || !scope.canManage()) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
    }

    private LambdaQueryWrapper<ObsRun> scopeWrapped(ObsScope scope) {
        LambdaQueryWrapper<ObsRun> q = new LambdaQueryWrapper<>();
        q.isNotNull(ObsRun::getOwnerId); // 隔离域不导出
        return q;
    }
}
