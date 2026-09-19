package com.ghy.mutiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ghy.mutiagent.model.HistoryItem;
import com.ghy.mutiagent.model.SessionAuditPage;
import com.ghy.mutiagent.model.SessionAuditRow;
import com.ghy.mutiagent.model.UsageAdminSummary;
import com.ghy.mutiagent.model.UsageAlertRow;
import com.ghy.mutiagent.model.UsageFunnelStep;
import com.ghy.mutiagent.model.UsageGroupSummary;
import com.ghy.mutiagent.model.UsagePageResult;
import com.ghy.mutiagent.model.UsageSummary;
import com.ghy.mutiagent.model.UsageTrendPoint;
import com.ghy.mutiagent.model.UsageUserSummary;
import com.ghy.mutiagent.repository.entity.TravelSessionState;
import com.ghy.mutiagent.repository.entity.UsageRecord;
import com.ghy.mutiagent.repository.mapper.TravelSessionStateMapper;
import com.ghy.mutiagent.repository.mapper.UsageRecordMapper;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用量记录服务：把用户操作日志、Agent token 消耗与 Q&A 审计流水落库（t_usage_record）。
 *
 * - 纯操作（对话/确认/翻页/路径/评价）token 为 0，带 channel 渠道标记；
 * - Agent 调用记录输入/输出 token、模型名、渠道与原始输出（截断存储）；
 * - 每轮对话记录 question/answer 一条，管理员可审计"问了什么、答了什么、走了哪条链路"；
 * - 记录失败只告警不抛异常，绝不阻断业务流程。
 */
@Service
public class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);

    private static final int REMARK_MAX = 500;
    private static final int QUESTION_MAX = 1000;
    private static final int ANSWER_MAX = 2000;
    private static final int AGG_MAX = 2000;

    private final UsageRecordMapper usageRecordMapper;
    private final ModelPricing modelPricing;
    private final TravelSessionStateMapper travelSessionStateMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    public UsageService(UsageRecordMapper usageRecordMapper, ModelPricing modelPricing,
                        TravelSessionStateMapper travelSessionStateMapper,
                        com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.usageRecordMapper = usageRecordMapper;
        this.modelPricing = modelPricing;
        this.travelSessionStateMapper = travelSessionStateMapper;
        this.objectMapper = objectMapper;
    }

    /** 兼容旧构造（单测等场景）：会话快照关联与目的地解析降级为跳过 */
    public UsageService(UsageRecordMapper usageRecordMapper, ModelPricing modelPricing) {
        this(usageRecordMapper, modelPricing, null, null);
    }

    // ==================== 记录 ====================

    /** 纯操作记录（token 为 0） */
    public void record(String sessionId, Long userId, String username, String stage, String action,
                       String status, String remark) {
        record(sessionId, userId, username, stage, action, null,
                0, 0, 0, status, remark, null, null, null, null);
    }

    /** 纯操作记录 + 渠道 */
    public void recordOp(String sessionId, Long userId, String username, String stage, String action,
                         String status, String remark, String channel) {
        record(sessionId, userId, username, stage, action, null,
                0, 0, 0, status, remark, null, channel, null, null);
    }

    /** Agent 调用记录（带 token 与耗时） */
    public void record(String sessionId, Long userId, String username, String stage, String action,
                       String agent, TokenUsage usage, long durationMs, String status, String remark) {
        record(sessionId, userId, username, stage, action, agent,
                usage == null ? 0 : nvl(usage.inputTokenCount()),
                usage == null ? 0 : nvl(usage.outputTokenCount()),
                durationMs, status, remark, null, null, null, null);
    }

    /** Agent 调用记录 + 模型/渠道/原始输出（审计用） */
    public void recordAgent(String sessionId, Long userId, String username, String stage, String action,
                            String agent, TokenUsage usage, long durationMs, String status, String remark,
                            String model, String channel, String answer) {
        record(sessionId, userId, username, stage, action, agent,
                usage == null ? 0 : nvl(usage.inputTokenCount()),
                usage == null ? 0 : nvl(usage.outputTokenCount()),
                durationMs, status, remark, model, channel, null, answer);
    }

    /** 每轮对话 Q&A 记录：用户问题 + 回复文本 + 本轮渠道/模型/token 合计 */
    public void recordQa(String sessionId, Long userId, String username, String stage, String action,
                         String status, String channel, String model, String question, String answer,
                         int inputTokens, int outputTokens, long durationMs) {
        record(sessionId, userId, username, stage, action, null,
                inputTokens, outputTokens, durationMs, status, null,
                model, channel, question, answer);
    }

    private void record(String sessionId, Long userId, String username, String stage, String action,
                        String agent, int inputTokens, int outputTokens, long durationMs,
                        String status, String remark, String model, String channel,
                        String question, String answer) {
        try {
            UsageRecord r = new UsageRecord();
            r.setUserId(userId == null ? 0L : userId);
            r.setUsername(username);
            r.setSessionId(sessionId);
            r.setStage(stage);
            r.setAction(action);
            r.setAgent(agent);
            r.setInputTokens(inputTokens);
            r.setOutputTokens(outputTokens);
            r.setTotalTokens(inputTokens + outputTokens);
            r.setDurationMs(durationMs);
            r.setStatus(status);
            r.setModel(model);
            r.setChannel(channel);
            r.setQuestion(clip(question, QUESTION_MAX));
            r.setAnswer(clip(answer, ANSWER_MAX));
            r.setRemark(clip(remark, REMARK_MAX));
            usageRecordMapper.insert(r);
        } catch (Exception e) {
            log.warn("用量记录落库失败: {}", e.getMessage());
        }
    }

    /** 文本截断（超过 max 截断并加省略号） */
    public static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ==================== 查询 ====================

    /** 最近用量明细（all=true 管理员看全部；否则只看自己）；行级费用附在 cost 字段 */
    public List<UsageRecord> recent(Long userId, boolean all, int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        List<UsageRecord> list = usageRecordMapper.selectList(new LambdaQueryWrapper<UsageRecord>()
                .eq(!all, UsageRecord::getUserId, userId == null ? 0L : userId)
                .orderByDesc(UsageRecord::getId)
                .last("LIMIT " + lim));
        list.forEach(this::fillCost);
        return list;
    }

    /** 会话聊天历史回放（断点恢复窗口）：从 Q&A 审计流水按时间正序组装 user/assistant 条目 */
    public List<HistoryItem> chatHistory(String sessionId, int limit) {
        List<HistoryItem> items = new ArrayList<>();
        if (sessionId == null || sessionId.isBlank()) {
            return items;
        }
        List<Map<String, Object>> rows;
        try {
            rows = usageRecordMapper.selectQaHistory(sessionId,
                    Math.max(1, Math.min(limit, 200)));
        } catch (Exception e) {
            // 历史回放失败不阻断恢复（会话状态本身仍可恢复），降级为空历史
            log.warn("会话聊天历史读取失败: {}", e.getMessage());
            return items;
        }
        for (Map<String, Object> row : rows) {
            String question = row.get("question") == null ? "" : String.valueOf(row.get("question"));
            String answer = row.get("answer") == null ? "" : String.valueOf(row.get("answer"));
            // 只有真实用户提问才算一轮对话；空提问的「重新询问」回放无意义，整行跳过
            if (question.isBlank()) {
                continue;
            }
            HistoryItem q = new HistoryItem();
            q.setRole("user");
            q.setText(question);
            items.add(q);
            if (!answer.isBlank()) {
                HistoryItem a = new HistoryItem();
                a.setRole("assistant");
                a.setText(answer);
                items.add(a);
            }
        }
        return items;
    }

    /** 管理员分页查询（可按用户/阶段/渠道/日期过滤），行级费用附在 cost 字段 */
    public UsagePageResult page(Long userId, String stage, String channel,
                                LocalDate dateFrom, LocalDate dateTo, int limit, int offset) {
        int lim = Math.max(1, Math.min(limit, 200));
        int off = Math.max(0, offset);
        LambdaQueryWrapper<UsageRecord> w = new LambdaQueryWrapper<UsageRecord>()
                .eq(userId != null, UsageRecord::getUserId, userId)
                .eq(stage != null && !stage.isBlank(), UsageRecord::getStage, stage)
                .eq(channel != null && !channel.isBlank(), UsageRecord::getChannel, channel)
                .ge(dateFrom != null, UsageRecord::getCreatedAt,
                        dateFrom == null ? null : dateFrom.atStartOfDay())
                .lt(dateTo != null, UsageRecord::getCreatedAt,
                        dateTo == null ? null : dateTo.plusDays(1).atStartOfDay());
        long total = usageRecordMapper.selectCount(w);
        List<UsageRecord> list = usageRecordMapper.selectList(
                w.orderByDesc(UsageRecord::getId).last("LIMIT " + lim + " OFFSET " + off));
        list.forEach(this::fillCost);
        UsagePageResult p = new UsagePageResult();
        p.setTotal(total);
        p.setList(list);
        return p;
    }

    // ==================== 会话维度审计（管理员） ====================

    /**
     * 会话维度分页：把用量记录按 session_id 聚合为一行（窗口同 AGG_MAX，内存聚合保证费用按模型精确核算）。
     * 结束标准：会话快照存在已发布行程（stage=DONE 或 state_json.plan 非空）视为「成功」——调整只是同一规划的新版本。
     */
    public SessionAuditPage sessionPage(Long userId, String username, String sessionId, String lastStage,
                                        String resultStatus, LocalDate dateFrom, LocalDate dateTo,
                                        int limit, int offset) {
        int lim = Math.max(1, Math.min(limit, 100));
        int off = Math.max(0, offset);
        List<UsageRecord> rows = usageRecordMapper.selectList(new LambdaQueryWrapper<UsageRecord>()
                .eq(userId != null, UsageRecord::getUserId, userId)
                .like(username != null && !username.isBlank(), UsageRecord::getUsername, username)
                .like(sessionId != null && !sessionId.isBlank(), UsageRecord::getSessionId, sessionId)
                .ge(dateFrom != null, UsageRecord::getCreatedAt,
                        dateFrom == null ? null : dateFrom.atStartOfDay())
                .lt(dateTo != null, UsageRecord::getCreatedAt,
                        dateTo == null ? null : dateTo.plusDays(1).atStartOfDay())
                .orderByDesc(UsageRecord::getId)
                .last("LIMIT " + AGG_MAX));
        Map<String, List<UsageRecord>> bySession = new LinkedHashMap<>();
        for (UsageRecord r : rows) {
            bySession.computeIfAbsent(r.getSessionId() == null ? "（无会话）" : r.getSessionId(),
                    k -> new ArrayList<>()).add(r);
        }
        List<Map.Entry<String, List<UsageRecord>>> groups = new ArrayList<>(bySession.entrySet());
        // 会话按组内最新一条记录倒序（最近规划的会话排前面）
        groups.sort(Comparator.comparingLong(
                (Map.Entry<String, List<UsageRecord>> e) -> e.getValue().stream()
                        .mapToLong(r -> r.getId() == null ? 0 : r.getId()).max().orElse(0)).reversed());
        Map<String, SessionStateInfo> stateBySession = loadSessionStates(groups);
        LocalDateTime now = LocalDateTime.now();
        List<SessionAuditRow> list = new ArrayList<>();
        for (Map.Entry<String, List<UsageRecord>> g : groups) {
            List<UsageRecord> rs = g.getValue();
            SessionAuditRow row = new SessionAuditRow();
            row.setSessionId(g.getKey());
            row.setSubTask(g.getKey().startsWith("adj-"));
            SessionStateInfo stInfo = stateBySession.get(g.getKey());
            row.setDestinationName(stInfo == null ? null : stInfo.destinationName());
            row.setRecordCount(rs.size());
            int in = 0;
            int out = 0;
            int tot = 0;
            long agentMs = 0;
            int adjustCount = 0;
            BigDecimal cost = BigDecimal.ZERO;
            LocalDateTime first = null;
            LocalDateTime last = null;
            UsageRecord latest = null;
            boolean anyFailed = false;
            boolean anyTimeout = false;
            boolean restarted = false;
            String failRemark = null;
            String failAction = null;
            String failStage = null;
            for (UsageRecord r : rs) {
                in += nvl(r.getInputTokens());
                out += nvl(r.getOutputTokens());
                tot += nvl(r.getTotalTokens());
                cost = addCost(cost, costOf(r));
                if (r.getModel() != null && !r.getModel().isBlank() && r.getDurationMs() != null) {
                    agentMs += r.getDurationMs();
                }
                if ("调整行程".equals(r.getAction())) {
                    adjustCount++;
                }
                if (first == null || (r.getCreatedAt() != null && r.getCreatedAt().isBefore(first))) {
                    first = r.getCreatedAt();
                }
                if (last == null || (r.getCreatedAt() != null && r.getCreatedAt().isAfter(last))) {
                    last = r.getCreatedAt();
                }
                if (latest == null || (r.getId() != null
                        && (latest.getId() == null || r.getId() > latest.getId()))) {
                    latest = r;
                }
                if ("FAILED".equals(r.getStatus())) {
                    anyFailed = true;
                    if (r.getRemark() != null && r.getRemark().contains("Timeout")) {
                        anyTimeout = true;
                    }
                    if (failRemark == null) {
                        failRemark = r.getRemark();
                        failAction = r.getAction();
                        failStage = r.getStage();
                    }
                }
                if (r.getAnswer() != null && r.getAnswer().contains("重新开始")) {
                    restarted = true;
                }
            }
            row.setInputTokens(in);
            row.setOutputTokens(out);
            row.setTotalTokens(tot);
            row.setCost(cost);
            row.setFirstAt(first);
            row.setLastAt(last);
            row.setTotalAgentMs(agentMs);
            row.setAdjustCount(adjustCount);
            if (latest != null) {
                row.setUsername(latest.getUsername());
                row.setLastStage(latest.getStage());
            }
            boolean expired = stInfo != null
                    ? (stInfo.expiresAt() != null && stInfo.expiresAt().isBefore(now))
                    : (last != null && last.isBefore(now.minusHours(2)));
            String status = anyTimeout ? "TIMEOUT" : anyFailed ? "FAILED"
                    : (stInfo != null && stInfo.published()) ? "DONE"
                    : restarted ? "RESTARTED"
                    : expired ? "EXPIRED" : "ONGOING";
            row.setResultStatus(status);
            row.setFailRemark(failRemark == null ? null
                    : failRemark.substring(0, Math.min(80, failRemark.length())));
            row.setFailAction(failAction);
            row.setFailStage(failStage);
            // 组后过滤：最后阶段 / 结果
            if (lastStage != null && !lastStage.isBlank()
                    && !lastStage.equals(latest == null ? null : latest.getStage())) {
                continue;
            }
            if (resultStatus != null && !resultStatus.isBlank() && !resultStatus.equals(status)) {
                continue;
            }
            list.add(row);
        }
        SessionAuditPage p = new SessionAuditPage();
        p.setTotal(list.size());
        p.setList(off >= list.size() ? List.of() : new ArrayList<>(list.subList(off, Math.min(off + lim, list.size()))));
        return p;
    }

    /** 会话快照侧信息（目的地 / 权威阶段 / 是否已发布行程 / 过期时间） */
    private record SessionStateInfo(String destinationName, boolean published, LocalDateTime expiresAt) {
    }

    private Map<String, SessionStateInfo> loadSessionStates(
            List<Map.Entry<String, List<UsageRecord>>> groups) {
        Map<String, SessionStateInfo> out = new HashMap<>();
        if (travelSessionStateMapper == null) {
            return out;
        }
        List<String> sessionIds = groups.stream().map(Map.Entry::getKey)
                .filter(s -> !s.startsWith("adj-")).toList();
        if (sessionIds.isEmpty()) {
            return out;
        }
        try {
            for (TravelSessionState st : travelSessionStateMapper.selectBatchIds(sessionIds)) {
                out.put(st.getSessionId(), stateInfoOf(st));
            }
        } catch (Exception e) {
            log.warn("[Usage] 会话快照批量查询失败（目的地/状态缺失不影响审计）: {}", e.getMessage());
        }
        return out;
    }

    /** 解析会话快照：目的地名 + 是否已发布行程（stage=DONE 或 state_json.plan 非空）+ 过期时间 */
    private SessionStateInfo stateInfoOf(TravelSessionState st) {
        String destination = null;
        boolean published = "DONE".equals(st.getStage());
        if (objectMapper != null && st.getStateJson() != null && !st.getStateJson().isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode n = objectMapper.readTree(st.getStateJson());
                if (n != null) {
                    com.fasterxml.jackson.databind.JsonNode name = n.get("destinationName");
                    if (name != null && !name.isNull()) {
                        destination = name.asText();
                    }
                    com.fasterxml.jackson.databind.JsonNode plan = n.get("plan");
                    if (plan != null && !plan.isNull()) {
                        published = true;
                    }
                }
            } catch (Exception e) {
                // 解析失败按无快照处理
            }
        }
        return new SessionStateInfo(destination, published, st.getExpiresAt());
    }

    /** 单个会话的完整审计明细（按时间正序；行级费用附在 cost 字段） */
    public List<UsageRecord> sessionRecords(String sessionId) {
        List<UsageRecord> list = usageRecordMapper.selectList(new LambdaQueryWrapper<UsageRecord>()
                .eq(UsageRecord::getSessionId, sessionId)
                .orderByAsc(UsageRecord::getId));
        list.forEach(this::fillCost);
        return list;
    }

    // ==================== 审计台分析（管理员） ====================

    /** 按天用量趋势（调用次数 / token / 费用），窗口内无记录日期补零 */
    public List<UsageTrendPoint> trend(int days) {
        int d = Math.max(1, Math.min(days, 30));
        LocalDate from = LocalDate.now().minusDays(d - 1L);
        List<UsageRecord> rows = usageRecordMapper.selectList(new LambdaQueryWrapper<UsageRecord>()
                .ge(UsageRecord::getCreatedAt, from.atStartOfDay())
                .orderByDesc(UsageRecord::getId)
                .last("LIMIT " + AGG_MAX));
        Map<LocalDate, UsageTrendPoint> byDate = new java.util.TreeMap<>();
        for (UsageRecord r : rows) {
            if (r.getCreatedAt() == null) {
                continue;
            }
            LocalDate day = r.getCreatedAt().toLocalDate();
            UsageTrendPoint p = byDate.computeIfAbsent(day, k -> {
                UsageTrendPoint x = new UsageTrendPoint();
                x.setDate(k.toString());
                return x;
            });
            p.setCalls(p.getCalls() + 1);
            p.setInputTokens(p.getInputTokens() + nvl(r.getInputTokens()));
            p.setOutputTokens(p.getOutputTokens() + nvl(r.getOutputTokens()));
            p.setTotalTokens(p.getTotalTokens() + nvl(r.getTotalTokens()));
            p.setCost(addCost(p.getCost(), costOf(r)));
        }
        for (LocalDate day = from; !day.isAfter(LocalDate.now()); day = day.plusDays(1)) {
            byDate.computeIfAbsent(day, k -> {
                UsageTrendPoint x = new UsageTrendPoint();
                x.setDate(k.toString());
                return x;
            });
        }
        return new ArrayList<>(byDate.values());
    }

    /**
     * 慢调用排行：双阈值筛选——单次 Agent 调用 ≥ callSec 秒，且所属会话的 AI 调用总耗时 ≥ sessionTotalSec 秒
     * （两者为 0 时不过滤对应维度），按单次耗时倒序取前 N 条。
     */
    public List<UsageRecord> slow(int sessionTotalSec, int callSec, int limit) {
        int lim = Math.max(1, Math.min(limit, 50));
        List<UsageRecord> rows = usageRecordMapper.selectList(new LambdaQueryWrapper<UsageRecord>()
                .isNotNull(UsageRecord::getModel)
                .orderByDesc(UsageRecord::getId)
                .last("LIMIT " + AGG_MAX));
        Map<String, Long> totalBySession = new HashMap<>();
        for (UsageRecord r : rows) {
            String key = r.getSessionId() == null ? "（无会话）" : r.getSessionId();
            totalBySession.merge(key, r.getDurationMs() == null ? 0L : r.getDurationMs(), Long::sum);
        }
        List<UsageRecord> out = rows.stream()
                .filter(r -> callSec <= 0 || (r.getDurationMs() != null && r.getDurationMs() >= callSec * 1000L))
                .filter(r -> sessionTotalSec <= 0 || totalBySession.getOrDefault(
                        r.getSessionId() == null ? "（无会话）" : r.getSessionId(), 0L)
                        >= sessionTotalSec * 1000L)
                .sorted(Comparator.comparing(UsageRecord::getDurationMs,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(lim)
                .toList();
        out.forEach(this::fillCost);
        return out;
    }

    /** 失败告警：窗口内有 FAILED 记录的会话（按会话聚合失败次数与最近失败详情） */
    public List<UsageAlertRow> alerts(int hours) {
        int h = Math.max(1, Math.min(hours, 72));
        LocalDateTime since = LocalDateTime.now().minusHours(h);
        List<UsageRecord> rows = usageRecordMapper.selectList(new LambdaQueryWrapper<UsageRecord>()
                .eq(UsageRecord::getStatus, "FAILED")
                .ge(UsageRecord::getCreatedAt, since)
                .orderByDesc(UsageRecord::getId)
                .last("LIMIT " + AGG_MAX));
        Map<String, UsageAlertRow> bySession = new LinkedHashMap<>();
        for (UsageRecord r : rows) {
            String key = r.getSessionId() == null ? "（无会话）" : r.getSessionId();
            UsageAlertRow a = bySession.computeIfAbsent(key, k -> new UsageAlertRow());
            if (a.getFailCount() == 0) {
                a.setSessionId(key);
                a.setUsername(r.getUsername());
                a.setAction(r.getAction());
                a.setModel(r.getModel());
                a.setLastFailedAt(r.getCreatedAt());
                a.setRemark(r.getRemark() == null ? ""
                        : r.getRemark().substring(0, Math.min(120, r.getRemark().length())));
            }
            a.setFailCount(a.getFailCount() + 1);
        }
        List<UsageAlertRow> out = new ArrayList<>(bySession.values());
        out.sort(Comparator.comparing(UsageAlertRow::getLastFailedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return out.size() > 10 ? new ArrayList<>(out.subList(0, 10)) : out;
    }

    /** 会话漏斗：创建会话 → 景点选择 → 美食选择 → 行程生成 → 完成行程（去重会话数） */
    public List<UsageFunnelStep> funnel(Long userId, LocalDate dateFrom, LocalDate dateTo) {
        List<UsageRecord> rows = usageRecordMapper.selectList(new LambdaQueryWrapper<UsageRecord>()
                .eq(userId != null, UsageRecord::getUserId, userId)
                .ge(dateFrom != null, UsageRecord::getCreatedAt,
                        dateFrom == null ? null : dateFrom.atStartOfDay())
                .lt(dateTo != null, UsageRecord::getCreatedAt,
                        dateTo == null ? null : dateTo.plusDays(1).atStartOfDay())
                .orderByDesc(UsageRecord::getId)
                .last("LIMIT " + AGG_MAX));
        java.util.Set<String> created = new java.util.HashSet<>();
        java.util.Set<String> att = new java.util.HashSet<>();
        java.util.Set<String> food = new java.util.HashSet<>();
        java.util.Set<String> generating = new java.util.HashSet<>();
        java.util.Set<String> doneFallback = new java.util.HashSet<>();
        for (UsageRecord r : rows) {
            String sid = r.getSessionId();
            if (sid == null || sid.startsWith("adj-")) {
                continue;
            }
            if ("创建会话".equals(r.getAction())) {
                created.add(sid);
            }
            if ("ATTRACTIONS".equals(r.getStage())) {
                att.add(sid);
            }
            if ("FOODS".equals(r.getStage())) {
                food.add(sid);
            }
            if ("ITINERARY".equals(r.getStage())) {
                generating.add(sid);
            }
            if ("DONE".equals(r.getStage())) {
                doneFallback.add(sid);
            }
        }
        // 完成口径：会话快照 stage=DONE（权威）；快照缺失时回退到 DONE 阶段对话行
        java.util.Set<String> done = new java.util.HashSet<>(doneFallback);
        java.util.Set<String> all = new java.util.HashSet<>(created);
        all.addAll(att);
        all.addAll(food);
        all.addAll(generating);
        if (travelSessionStateMapper != null && !all.isEmpty()) {
            try {
                for (TravelSessionState st : travelSessionStateMapper.selectBatchIds(new ArrayList<>(all))) {
                    if ("DONE".equals(st.getStage())) {
                        done.add(st.getSessionId());
                    }
                }
            } catch (Exception e) {
                log.warn("[Usage] 漏斗完成口径回退（会话快照查询失败）: {}", e.getMessage());
            }
        }
        List<UsageFunnelStep> out = new ArrayList<>();
        addFunnel(out, "创建会话", created.size());
        addFunnel(out, "景点选择", att.size());
        addFunnel(out, "美食选择", food.size());
        addFunnel(out, "行程生成", generating.size());
        addFunnel(out, "完成行程", done.size());
        return out;
    }

    private static void addFunnel(List<UsageFunnelStep> out, String label, int sessions) {
        UsageFunnelStep s = new UsageFunnelStep();
        s.setLabel(label);
        s.setSessions(sessions);
        out.add(s);
    }

    /** 按阶段汇总 token 与调用次数（含费用估算） */
    public List<UsageSummary> summary(Long userId, boolean all) {
        List<UsageRecord> records = usageRecordMapper.selectList(new LambdaQueryWrapper<UsageRecord>()
                .eq(!all, UsageRecord::getUserId, userId == null ? 0L : userId)
                .orderByDesc(UsageRecord::getId)
                .last("LIMIT " + AGG_MAX));
        Map<String, UsageSummary> byStage = new LinkedHashMap<>();
        for (UsageRecord r : records) {
            String stage = r.getStage() == null ? "未知" : r.getStage();
            UsageSummary s = byStage.computeIfAbsent(stage, k -> {
                UsageSummary u = new UsageSummary();
                u.setStage(k);
                return u;
            });
            s.setCount(s.getCount() + 1);
            s.setTotalInputTokens(s.getTotalInputTokens() + nvl(r.getInputTokens()));
            s.setTotalOutputTokens(s.getTotalOutputTokens() + nvl(r.getOutputTokens()));
            s.setTotalTokens(s.getTotalTokens() + nvl(r.getTotalTokens()));
            s.setTotalDurationMs(s.getTotalDurationMs() + (r.getDurationMs() == null ? 0 : r.getDurationMs()));
            s.setTotalCost(addCost(s.getTotalCost(), costOf(r)));
        }
        List<UsageSummary> out = new ArrayList<>(byStage.values());
        out.sort(Comparator.comparing(UsageSummary::getStage));
        return out;
    }

    /** 管理员多维汇总：按用户 / 模型 / 阶段 / 渠道（含费用估算） */
    public UsageAdminSummary adminSummary() {
        List<UsageRecord> rows = usageRecordMapper.selectList(new LambdaQueryWrapper<UsageRecord>()
                .orderByDesc(UsageRecord::getId)
                .last("LIMIT " + AGG_MAX));
        UsageAdminSummary out = new UsageAdminSummary();
        Map<String, UsageGroupSummary> byStage = new LinkedHashMap<>();
        Map<String, UsageGroupSummary> byChannel = new LinkedHashMap<>();
        Map<String, UsageGroupSummary> byModel = new LinkedHashMap<>();
        Map<String, UsageUserSummary> byUser = new LinkedHashMap<>();

        for (UsageRecord r : rows) {
            BigDecimal cost = costOf(r);
            accumulate(byStage, r.getStage() == null ? "未知" : r.getStage(), r, cost);
            accumulate(byChannel, r.getChannel() == null ? "OP" : r.getChannel(), r, cost);
            if (r.getModel() != null && !r.getModel().isBlank()) {
                accumulate(byModel, r.getModel(), r, cost);
            }
        }

        // 用户维度：初始化 + 累加
        for (UsageRecord r : rows) {
            String name = r.getUsername() == null || r.getUsername().isBlank() ? "匿名" : r.getUsername();
            UsageUserSummary u = byUser.get(name);
            if (u == null) {
                u = new UsageUserSummary();
                u.setUsername(name);
                u.setUserId(r.getUserId());
                byUser.put(name, u);
            }
            u.setCount(u.getCount() + 1);
            u.setInputTokens(u.getInputTokens() + nvl(r.getInputTokens()));
            u.setOutputTokens(u.getOutputTokens() + nvl(r.getOutputTokens()));
            u.setTotalTokens(u.getTotalTokens() + nvl(r.getTotalTokens()));
            u.setDurationMs(u.getDurationMs() + (r.getDurationMs() == null ? 0 : r.getDurationMs()));
            u.setCost(addCost(u.getCost(), costOf(r)));
            if (r.getCreatedAt() != null && (u.getLastActiveAt() == null
                    || r.getCreatedAt().isAfter(u.getLastActiveAt()))) {
                u.setLastActiveAt(r.getCreatedAt());
            }
        }

        List<UsageGroupSummary> stageList = new ArrayList<>(byStage.values());
        stageList.sort(Comparator.comparing(UsageGroupSummary::getKey));
        List<UsageGroupSummary> channelList = new ArrayList<>(byChannel.values());
        channelList.sort(Comparator.comparing(UsageGroupSummary::getKey));
        List<UsageGroupSummary> modelList = new ArrayList<>(byModel.values());
        modelList.sort(Comparator.comparingLong(UsageGroupSummary::getTotalTokens).reversed());
        List<UsageUserSummary> userList = new ArrayList<>(byUser.values());
        userList.sort(Comparator.comparingLong(UsageUserSummary::getTotalTokens).reversed());

        out.setByStage(stageList);
        out.setByChannel(channelList);
        out.setByModel(modelList);
        out.setByUser(userList);
        return out;
    }

    // ==================== 内部 ====================

    private void accumulate(Map<String, UsageGroupSummary> m, String key, UsageRecord r, BigDecimal cost) {
        UsageGroupSummary g = m.computeIfAbsent(key, k -> {
            UsageGroupSummary x = new UsageGroupSummary();
            x.setKey(k);
            return x;
        });
        g.setCount(g.getCount() + 1);
        g.setInputTokens(g.getInputTokens() + nvl(r.getInputTokens()));
        g.setOutputTokens(g.getOutputTokens() + nvl(r.getOutputTokens()));
        g.setTotalTokens(g.getTotalTokens() + nvl(r.getTotalTokens()));
        g.setDurationMs(g.getDurationMs() + (r.getDurationMs() == null ? 0 : r.getDurationMs()));
        g.setCost(addCost(g.getCost(), cost));
    }

    private void fillCost(UsageRecord r) {
        r.setCost(costOf(r));
    }

    private BigDecimal costOf(UsageRecord r) {
        return modelPricing.estimate(r.getModel(), nvl(r.getInputTokens()), nvl(r.getOutputTokens()));
    }

    private static BigDecimal addCost(BigDecimal a, BigDecimal b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.add(b);
    }

    private static int nvl(Integer v) {
        return v == null ? 0 : v;
    }
}
