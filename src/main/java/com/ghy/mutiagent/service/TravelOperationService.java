package com.ghy.mutiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.model.enums.TravelStage;
import com.ghy.mutiagent.repository.entity.Itinerary;
import com.ghy.mutiagent.repository.entity.TravelOperation;
import com.ghy.mutiagent.repository.entity.TravelSessionState;
import com.ghy.mutiagent.repository.mapper.ItineraryMapper;
import com.ghy.mutiagent.repository.mapper.TravelOperationMapper;
import com.ghy.mutiagent.repository.mapper.TravelSessionStateMapper;
import com.ghy.mutiagent.rule.ItineraryTextRenderer;
import com.ghy.mutiagent.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 会话写操作协议（S06-B）：requestId 幂等 + attemptNo 执行权栅栏 + VALIDATED 草案恢复。
 *
 * T1 领取执行权（锁会话行 → 校验 → 插 RUNNING 操作并占用会话）；
 * 事务外模型与规则计算（记录提供方调用事实）；结果校验后保存 VALIDATED 草案；
 * T2 短事务提交（行程行 + 会话快照 revision+1 + 操作 COMPLETED，同一 MySQL 事务）。
 *
 * 所有状态推进都带 attemptNo/状态守卫：过期执行者写回影响 0 行即失败。
 * UNKNOWN（提供方已响应但草案未保存）保留占用、禁止自动重放。
 */
@Service
public class TravelOperationService {

    private static final Logger log = LoggerFactory.getLogger(TravelOperationService.class);

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_VALIDATED = "VALIDATED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_UNKNOWN = "UNKNOWN";
    /** S08 终态：修复上限/不可修复/预算耗尽——保留未提交草稿与冲突说明，等待用户确认 */
    public static final String STATUS_NEEDS_CONFIRMATION = "NEEDS_CONFIRMATION";
    /** S08 终态：共享 deadline 已过（含队列等待），不得再发起提供方调用 */
    public static final String STATUS_DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED";
    /** S11 终态：显式取消 / SSE 断开——晚到结果在提交前被取消检查拒绝 */
    public static final String STATUS_CANCELLED = "CANCELLED";
    /** S11 终态：有界队列满快速拒绝——入队失败立即释放操作占位，客户端可凭原 requestId 重试 */
    public static final String STATUS_BUSY = "BUSY";

    /** 生成（重新规划）操作动作名：受理快照必须剔除旧行程 */
    public static final String ACTION_GENERATE = "GENERATE_ITINERARY";

    private final TravelOperationMapper operationMapper;
    private final TravelSessionStateMapper sessionStateMapper;
    private final ItineraryMapper itineraryMapper;
    private final ItineraryCommitService commitService;
    private final ObjectMapper objectMapper;
    private final long leaseMs;
    /** S11 取消注册表（可选装配）：持久化取消在操作行，进程内信号通知在途执行线程 */
    private CancelRegistry cancelRegistry;

    public TravelOperationService(TravelOperationMapper operationMapper,
                                  TravelSessionStateMapper sessionStateMapper,
                                  ItineraryMapper itineraryMapper,
                                  ItineraryCommitService commitService,
                                  ObjectMapper objectMapper,
                                  @Value("${travel.operation.lease-ms:60000}") long leaseMs) {
        this.operationMapper = operationMapper;
        this.sessionStateMapper = sessionStateMapper;
        this.itineraryMapper = itineraryMapper;
        this.commitService = commitService;
        this.objectMapper = objectMapper;
        this.leaseMs = leaseMs;
    }

    @Autowired(required = false)
    public void setCancelRegistry(CancelRegistry cancelRegistry) {
        this.cancelRegistry = cancelRegistry;
    }

    /** S11 取消令牌（进程内信号）：未装配注册表时为 null（行级栅栏仍生效） */
    public CancelRegistry.CancelToken token(String operationId) {
        return cancelRegistry == null ? null : cancelRegistry.token(operationId);
    }

    /** 领取结果：reused=true 表示同 requestId 的幂等复用（不得继续执行） */
    public record OperationHandle(String operationId, int attemptNo, String status,
                                  Long itineraryId, TravelState frozen, boolean reused) {
    }

    /** T2 提交结果（finalStateJson 为已提交的新快照，供非权威缓存回填） */
    public record CommitOutcome(String operationId, Long itineraryId, long newRevision, String finalStateJson) {
    }

    /** 操作查询视图：只暴露状态与结果，不暴露内部 prompt/快照 */
    public record OperationView(String operationId, String status, Long baseRevision,
                                Long itineraryId, String errorCode, String errorDetail) {
    }

    public record RecoveryResult(String status, int attemptNo, CommitOutcome outcome) {
    }

    /** 校验后草案：adjust 操作携带旧版本 id，generate 为空；evidence 为 S08 规划证据（可为空） */
    public record ValidatedDraft(Long oldItineraryId, ItineraryPlan plan, Map<String, Object> evidence) {
    }

    // ==================== T1：领取执行权 ====================

    /**
     * 领取执行权（短事务）：同 actor+requestId 幂等复用；不同 hash → IDEMPOTENCY_CONFLICT；
     * 同会话其他操作在途 / revision 不符 / 会话过期 → 拒绝。持有会话行锁，保证领取串行化。
     */
    @Transactional(rollbackFor = Exception.class)
    public OperationHandle acquire(AuthenticatedUser actor, String sessionId, String requestId,
                                   long expectedRevision, String action, String bodyJson) {
        try {
            return doAcquire(actor, sessionId, requestId, expectedRevision, action, bodyJson);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // 权威 DB 不可用（T1 前故障）：503，不得推进到模型调用
            log.error("[Op] 领取执行权失败（权威存储不可用）: {}", e.getMessage());
            throw new BizException(ResultCode.STORAGE_UNAVAILABLE);
        }
    }

    private OperationHandle doAcquire(AuthenticatedUser actor, String sessionId, String requestId,
                                      long expectedRevision, String action, String bodyJson) {
        String hash = requestHash(actor.id(), sessionId, requestId, action, expectedRevision, bodyJson);

        // 先锁会话行（锁定读不建立一致性快照），再做幂等回查：
        // 同键竞争胜者提交后必可见，避免 REPEATABLE READ 旧快照误判「操作不存在」
        TravelSessionState s = sessionStateMapper.selectForUpdate(sessionId);
        TravelOperation existing = operationMapper.findByRequest(actor.id(), requestId);
        if (existing != null) {
            return reused(actor, sessionId, hash, existing);
        }

        if (s == null || s.getUserId() == null || !s.getUserId().equals(actor.id())) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        if (s.getExpiresAt() != null && s.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BizException(ResultCode.SESSION_EXPIRED);
        }
        if (s.getRevision() == null || s.getRevision() != expectedRevision) {
            throw new BizException(ResultCode.STATE_CONFLICT);
        }
        if (s.getActiveOperationId() != null) {
            throw new BizException(ResultCode.STATE_CONFLICT);
        }

        TravelOperation op = new TravelOperation();
        op.setId("op" + UUID.randomUUID().toString().replace("-", ""));
        op.setUserId(actor.id());
        op.setSessionId(sessionId);
        op.setRequestId(requestId);
        op.setAction(action);
        op.setRequestHash(hash);
        op.setBaseRevision(expectedRevision);
        op.setStatus(STATUS_RUNNING);
        op.setAttemptNo(1);
        op.setLeaseUntil(LocalDateTime.now().plus(leaseMs, ChronoUnit.MILLIS));
        TravelState frozen = parseState(s.getStateJson());
        if (ACTION_GENERATE.equals(action) && frozen != null) {
            // 重新生成：受理快照不带旧行程，后台执行才会真正调用提供方重规划
            // （快照携带旧 plan 会让 driveGenerate 跳过模型调用，直接重提交旧方案）
            frozen.setPlan(null);
            frozen.setItineraryId(null);
            frozen.setItineraryText(null);
            op.setInputSnapshot(serialize(frozen));
        } else {
            op.setInputSnapshot(s.getStateJson());
        }
        try {
            operationMapper.insert(op);
        } catch (DuplicateKeyException e) {
            // uk_actor_request 兜底：同键竞争由唯一约束裁决，回读已有操作
            TravelOperation winner = operationMapper.findByRequest(actor.id(), requestId);
            if (winner != null) {
                return reused(actor, sessionId, hash, winner);
            }
            throw e;
        }
        if (sessionStateMapper.claimOperation(sessionId, op.getId()) != 1) {
            throw new BizException(ResultCode.STATE_CONFLICT);
        }
        log.info("[Op] 领取执行权 {} requestId={} baseRevision={} attempt={}",
                op.getId(), requestId, expectedRevision, 1);
        return new OperationHandle(op.getId(), 1, STATUS_RUNNING, null, frozen, false);
    }

    private OperationHandle reused(AuthenticatedUser actor, String sessionId, String hash, TravelOperation op) {
        if (op.getUserId() == null || !op.getUserId().equals(actor.id())) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        if (!Objects.equals(op.getSessionId(), sessionId) || !op.getRequestHash().equals(hash)) {
            throw new BizException(ResultCode.IDEMPOTENCY_CONFLICT);
        }
        return new OperationHandle(op.getId(), op.getAttemptNo() == null ? 1 : op.getAttemptNo(),
                op.getStatus(), op.getItineraryId(), null, true);
    }

    // ==================== 事务外阶段：模型事实 / 草案 ====================

    /** 记录提供方已被调用（模型调用完成后的第一个恢复点，attemptNo 守卫） */
    @Transactional(rollbackFor = Exception.class)
    public void recordProviderAttempt(String operationId, int attemptNo) {
        if (operationMapper.recordProviderAttempt(operationId, attemptNo) != 1) {
            throw new BizException(ResultCode.STATE_CONFLICT);
        }
    }

    /** 草案通过校验并持久保存（T2 前的恢复点，attemptNo 守卫） */
    @Transactional(rollbackFor = Exception.class)
    public void saveValidatedDraft(String operationId, int attemptNo, String draftJson) {
        if (operationMapper.saveValidatedDraft(operationId, attemptNo, draftJson) != 1) {
            throw new BizException(ResultCode.STATE_CONFLICT);
        }
    }

    /** 明确失败：仅当前 attemptNo 持有者可标记并释放会话占用；errorDetail 为面向用户的违规详情 */
    @Transactional(rollbackFor = Exception.class)
    public void markFailed(String operationId, int attemptNo, String errorCode, String errorDetail) {
        TravelOperation op = operationMapper.selectById(operationId);
        if (op == null) {
            return;
        }
        if (operationMapper.markFailed(operationId, attemptNo, errorCode, errorDetail) == 1) {
            sessionStateMapper.releaseOperation(op.getSessionId(), operationId, attemptNo);
        }
    }

    /** S08 终止（NEEDS_CONFIRMATION / DEADLINE_EXCEEDED）：明确终态 + 原因码/冲突说明 + 释放会话占用 */
    @Transactional(rollbackFor = Exception.class)
    public void markTerminal(String operationId, int attemptNo, String status,
                             String errorCode, String errorDetail) {
        TravelOperation op = operationMapper.selectById(operationId);
        if (op == null) {
            return;
        }
        if (operationMapper.markTerminal(operationId, attemptNo, status, errorCode, errorDetail) == 1) {
            sessionStateMapper.releaseOperation(op.getSessionId(), operationId, attemptNo);
        }
    }

    /** S11 显式取消（SSE 断开同路径）：持久化取消标记 + 进程内信号；已终态操作幂等不受影响 */
    @Transactional(rollbackFor = Exception.class)
    public boolean cancel(AuthenticatedUser actor, String operationId) {
        TravelOperation op = operationMapper.selectById(operationId);
        if (op == null || op.getUserId() == null || !op.getUserId().equals(actor.id())) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        int attemptNo = op.getAttemptNo() == null ? 1 : op.getAttemptNo();
        boolean applied = operationMapper.cancel(operationId, attemptNo) == 1;
        if (applied) {
            sessionStateMapper.releaseOperation(op.getSessionId(), operationId, attemptNo);
        }
        if (cancelRegistry != null) {
            cancelRegistry.cancel(operationId);
        }
        return applied;
    }

    /** S11 入队失败（有界队列满）：置 BUSY 终态并释放会话占位（该操作刚被本请求创建，无在途执行线程） */
    @Transactional(rollbackFor = Exception.class)
    public boolean markBusy(String operationId) {
        TravelOperation op = operationMapper.selectById(operationId);
        if (op == null) {
            return false;
        }
        if (operationMapper.markBusy(operationId) == 1) {
            sessionStateMapper.releaseOperation(op.getSessionId(), operationId,
                    op.getAttemptNo() == null ? 1 : op.getAttemptNo());
            return true;
        }
        return false;
    }

    /** 后台执行句柄：按操作 id 重建执行上下文（owner 校验 + 冻结输入快照），仅 RUNNING 可继续执行 */
    public OperationHandle handleFor(String operationId, AuthenticatedUser actor) {
        TravelOperation op = operationMapper.selectById(operationId);
        if (op == null || op.getUserId() == null || !op.getUserId().equals(actor.id())) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        return new OperationHandle(op.getId(), op.getAttemptNo() == null ? 1 : op.getAttemptNo(),
                op.getStatus(), op.getItineraryId(), parseState(op.getInputSnapshot()), false);
    }

    // ==================== T2：提交业务 ====================

    /**
     * T2（短事务）：锁顺序 会话 → 操作 →（旧行程）；
     * 插入/归档行程、提交新快照（revision+1）、操作 COMPLETED 在同一 MySQL 事务内。
     * 只接受 VALIDATED 草案；任何守卫失败整体回滚。模型调用不在本事务内。
     */
    @Transactional(rollbackFor = Exception.class)
    public CommitOutcome commit(String operationId, int attemptNo) {
        TravelOperation op = operationMapper.selectById(operationId);
        if (op == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        TravelSessionState s = sessionStateMapper.selectForUpdate(op.getSessionId());
        if (s == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR);
        }
        TravelOperation locked = operationMapper.selectForUpdate(operationId);
        if (!Objects.equals(s.getActiveOperationId(), operationId)
                || locked.getAttemptNo() == null || locked.getAttemptNo() != attemptNo
                || !STATUS_VALIDATED.equals(locked.getStatus())) {
            throw new BizException(ResultCode.STATE_CONFLICT);
        }
        if (s.getRevision() == null || !s.getRevision().equals(locked.getBaseRevision())) {
            throw new BizException(ResultCode.STATE_CONFLICT);
        }

        TravelState state = parseState(s.getStateJson());
        ValidatedDraft draft = parseDraft(locked.getValidatedDraft());
        Itinerary row = ItineraryService.buildItineraryRow(state, draft.plan(), objectMapper);
        row.setOperationId(operationId);

        Long newItineraryId;
        if (draft.oldItineraryId() == null) {
            newItineraryId = commitService.commitNew(
                    new ItineraryCommitService.CommitNew(row)).newId();
        } else {
            Itinerary old = itineraryMapper.selectById(draft.oldItineraryId());
            if (old == null || old.getUserId() == null || !old.getUserId().equals(state.getUserId())) {
                throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
            }
            if (!"ACTIVE".equals(old.getStatus())) {
                throw new BizException(ResultCode.STATE_CONFLICT);
            }
            try {
                newItineraryId = commitService.commitAdjustment(
                        new ItineraryCommitService.CommitAdjustment(draft.oldItineraryId(),
                                old.getVersion() == null ? 1 : old.getVersion(), row)).newId();
            } catch (java.sql.SQLException e) {
                // 声明受检异常同路径回滚：提交失败必须可见，不吞异常
                log.error("[Op] 调整提交失败（受检异常），事务回滚: {}", e.getMessage());
                throw new BizException(ResultCode.EXECUTE_ERROR);
            }
        }

        state.setPlan(draft.plan());
        state.setItineraryId(newItineraryId);
        state.setItineraryText(ItineraryTextRenderer.render(draft.plan()));
        state.setStage(TravelStage.DONE);
        long newRevision = s.getRevision() + 1;
        String newStateJson = serialize(state);
        if (sessionStateMapper.commitSnapshot(s.getSessionId(), s.getUserId(), s.getRevision(),
                newRevision, TravelStage.DONE.name(), newStateJson, s.getExpiresAt()) != 1) {
            throw new BizException(ResultCode.STATE_CONFLICT);
        }
        String resultJson;
        if (draft.evidence() == null || draft.evidence().isEmpty()) {
            resultJson = "{\"itineraryId\":" + newItineraryId + ",\"revision\":" + newRevision + "}";
        } else {
            // S08：规划证据（validationCodes/finalValidationCodes/repairAttempts）随提交结果留存，供审计与验收观察
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("itineraryId", newItineraryId);
            result.put("revision", newRevision);
            result.put("evidence", draft.evidence());
            resultJson = serialize(result);
        }
        if (operationMapper.complete(operationId, attemptNo, resultJson, newItineraryId) != 1) {
            throw new BizException(ResultCode.SYSTEM_ERROR);
        }
        log.info("[Op] 提交完成 {} itineraryId={} revision={}", operationId, newItineraryId, newRevision);
        return new CommitOutcome(operationId, newItineraryId, newRevision, newStateJson);
    }

    // ==================== 查询与恢复 ====================

    /** 操作查询：先检查 owner，只返回状态与结果（不返回内部 prompt/快照/异常栈） */
    public OperationView query(AuthenticatedUser actor, String operationId) {
        TravelOperation op = operationMapper.selectById(operationId);
        if (op == null || op.getUserId() == null || !op.getUserId().equals(actor.id())) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        return new OperationView(op.getId(), op.getStatus(), op.getBaseRevision(),
                op.getItineraryId(), op.getErrorCode(), op.getErrorDetail());
    }

    /**
     * 恢复核查（一次短事务）：
     * - VALIDATED：复用草案提交（不重新调用模型）；
     * - RUNNING 且提供方已被调用但无草案：标 UNKNOWN，保留占用，禁止自动重放；
     * - RUNNING 且租约过期、提供方未被调用：attemptNo+1 接管，旧执行者后续写回失效；
     * - 其余（COMPLETED/FAILED/UNKNOWN/租约内）：原样返回。
     */
    @Transactional(rollbackFor = Exception.class)
    public RecoveryResult recover(String operationId) {
        TravelOperation op = operationMapper.selectById(operationId);
        if (op == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        String status = op.getStatus() == null ? "" : op.getStatus();
        int attemptNo = op.getAttemptNo() == null ? 1 : op.getAttemptNo();
        switch (status) {
            case STATUS_VALIDATED -> {
                // 草案已持久化：复用提交（同一事务，锁顺序 会话→操作 与 T2 一致）
                CommitOutcome out = commit(operationId, attemptNo);
                return new RecoveryResult(STATUS_COMPLETED, attemptNo, out);
            }
            case STATUS_RUNNING -> {
                boolean expired = op.getLeaseUntil() != null
                        && op.getLeaseUntil().isBefore(LocalDateTime.now());
                if (!expired) {
                    // 仍在租约内：执行者可能还活着，恢复器不动它
                    return new RecoveryResult(STATUS_RUNNING, attemptNo, null);
                }
                if (op.getProviderAttemptAt() != null) {
                    operationMapper.markUnknown(operationId, attemptNo);
                    log.warn("[Op] {} 提供方已响应但草案未保存，恢复为 UNKNOWN（不自动重放）", operationId);
                    return new RecoveryResult(STATUS_UNKNOWN, attemptNo, null);
                }
                if (operationMapper.takeover(operationId, attemptNo,
                        LocalDateTime.now().plus(leaseMs, ChronoUnit.MILLIS),
                        LocalDateTime.now()) == 1) {
                    log.warn("[Op] {} 租约过期接管，attempt {} → {}（旧执行者写回将失效）",
                            operationId, attemptNo, attemptNo + 1);
                    return new RecoveryResult(STATUS_RUNNING, attemptNo + 1, null);
                }
                return new RecoveryResult(STATUS_RUNNING, attemptNo, null);
            }
            default -> {
                return new RecoveryResult(status, attemptNo, null);
            }
        }
    }

    // ==================== 草案封装与解析 ====================

    /** 草案持久化格式：generate 传 null，adjust 传旧版本 id */
    public String wrapDraft(Long oldItineraryId, ItineraryPlan plan) {
        return wrapDraft(oldItineraryId, plan, null);
    }

    /** 草案持久化格式（S08 带规划证据）：evidence 随草案保存，T2 提交时并入 resultJson */
    public String wrapDraft(Long oldItineraryId, ItineraryPlan plan, Map<String, Object> evidence) {
        return serialize(new ValidatedDraft(oldItineraryId, plan, evidence));
    }

    private ValidatedDraft parseDraft(String draftJson) {
        try {
            return objectMapper.readValue(draftJson, ValidatedDraft.class);
        } catch (Exception e) {
            log.error("草案解析失败: {}", e.getMessage());
            throw new BizException(ResultCode.SYSTEM_ERROR);
        }
    }

    private TravelState parseState(String stateJson) {
        try {
            return objectMapper.readValue(stateJson, TravelState.class);
        } catch (Exception e) {
            log.error("会话快照解析失败: {}", e.getMessage());
            throw new BizException(ResultCode.SYSTEM_ERROR);
        }
    }

    private String serialize(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.error("序列化失败: {}", e.getMessage());
            throw new BizException(ResultCode.SYSTEM_ERROR);
        }
    }

    /** 服务端规范化请求体计算 SHA-256（不信任客户端传来的 hash） */
    static String requestHash(Long userId, String sessionId, String requestId,
                              String action, long expectedRevision, String bodyJson) {
        String canonical = userId + "|" + sessionId + "|" + requestId + "|" + action
                + "|" + expectedRevision + "|" + (bodyJson == null ? "" : bodyJson);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
