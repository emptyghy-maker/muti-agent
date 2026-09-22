package com.ghy.mutiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.model.ResumeView;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.repository.entity.TravelSessionState;
import com.ghy.mutiagent.repository.mapper.TravelSessionStateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 规划会话存储（S06-B）：authority=db 时 MySQL 快照为权威，Redis 仅作可失效缓存；
 * authority=redis 时保持 S06-A 语义（Redis 唯一权威，写失败必须可见）。
 *
 * 语义约定：
 * - load：权威存储故障/数据损坏抛系统错误（不得伪装成「会话不存在」）；
 * - loadOwned：不存在或所有者不匹配统一按不可见处理（RESOURCE_NOT_FOUND）；
 * - save：权威写入失败必须向上抛错；非权威缓存失败只告警，不撤销已提交结果。
 */
@Service
public class TravelSessionService {

    private static final Logger log = LoggerFactory.getLogger(TravelSessionService.class);
    private static final String KEY_PREFIX = "travel:session:";
    private static final Duration TTL = Duration.ofHours(2);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final TravelSessionStateMapper sessionStateMapper;
    private final boolean dbAuthoritative;

    /** 断点恢复窗口：未结束会话距最近活动超过该时长即视为过期（滑动续期） */
    @Value("${travel.session.resume-ttl-ms:1800000}")
    private long resumeTtlMs = 1_800_000L;

    public TravelSessionService(StringRedisTemplate redis, ObjectMapper objectMapper,
                                TravelSessionStateMapper sessionStateMapper,
                                @Value("${travel.session.authority:db}") String authority) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.sessionStateMapper = sessionStateMapper;
        this.dbAuthoritative = "db".equalsIgnoreCase(authority);
    }

    /** 保存会话：权威存储失败必须可见；DB 权威时 Redis 缓存失败只告警（S06-B） */
    public void save(TravelState state) {
        if (dbAuthoritative) {
            saveToDb(state);
            cacheBestEffort(state);
            return;
        }
        try {
            redis.opsForValue().set(KEY_PREFIX + state.getSessionId(),
                    objectMapper.writeValueAsString(state), TTL);
        } catch (Exception e) {
            log.error("会话保存失败（存储不可用），本轮结果不可信: {}", e.getMessage());
            throw new BizException(ResultCode.SYSTEM_ERROR);
        }
    }

    /** 尽力写缓存（非权威）：失败不撤销已提交的 DB 事务、不重新调用模型 */
    public void cacheBestEffort(TravelState state) {
        try {
            redis.opsForValue().set(KEY_PREFIX + state.getSessionId(),
                    objectMapper.writeValueAsString(state), TTL);
        } catch (Exception e) {
            log.warn("会话缓存写失败（非权威缓存，忽略）: {}", e.getMessage());
        }
    }

    /** DB 权威快照落库：不存在则插入、存在则更新；存储异常必须可见 */
    private void saveToDb(TravelState state) {
        try {
            TravelSessionState existing = sessionStateMapper.selectById(state.getSessionId());
            TravelSessionState row = existing == null ? new TravelSessionState() : existing;
            row.setSessionId(state.getSessionId());
            row.setUserId(state.getUserId() == null ? 0L : state.getUserId());
            row.setSchemaVersion(2);
            row.setStage(state.getStage() == null ? null : state.getStage().name());
            row.setStateJson(objectMapper.writeValueAsString(state));
            if (row.getRevision() == null) {
                row.setRevision(0L);
            }
            if (row.getExpiresAt() == null) {
                row.setExpiresAt(LocalDateTime.now().plusHours(2));
            }
            if (existing == null) {
                sessionStateMapper.insert(row);
            } else {
                sessionStateMapper.updateById(row);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("会话权威快照保存失败（DB 不可用）: {}", e.getMessage());
            throw new BizException(ResultCode.STORAGE_UNAVAILABLE);
        }
    }

    /** 读取会话：不存在返回 empty；权威存储故障/数据损坏抛系统错误，不伪装成过期 */
    public Optional<TravelState> load(String sessionId) {
        if (dbAuthoritative) {
            TravelSessionState row;
            try {
                row = sessionStateMapper.selectById(sessionId);
            } catch (Exception e) {
                log.error("会话权威快照读取失败（DB 不可用）: {}", e.getMessage());
                throw new BizException(ResultCode.STORAGE_UNAVAILABLE);
            }
            if (row == null) {
                return Optional.empty();
            }
            try {
                TravelState state = objectMapper.readValue(row.getStateJson(), TravelState.class);
                // 权威快照版本随读回填：客户端后续写操作以它为 expectedRevision（S06-B）
                state.setSessionRevision(row.getRevision());
                return Optional.of(state);
            } catch (Exception e) {
                log.error("会话快照数据损坏，无法反序列化: {}", e.getMessage());
                throw new BizException(ResultCode.STORAGE_UNAVAILABLE);
            }
        }
        String json;
        try {
            json = redis.opsForValue().get(KEY_PREFIX + sessionId);
        } catch (Exception e) {
            log.error("会话读取失败（Redis 异常）: {}", e.getMessage());
            throw new BizException(ResultCode.SYSTEM_ERROR);
        }
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, TravelState.class));
        } catch (Exception e) {
            log.error("会话数据损坏，无法反序列化: {}", e.getMessage());
            throw new BizException(ResultCode.SYSTEM_ERROR);
        }
    }

    /**
     * 按归属加载会话：不存在、无所有者记录、所有者不匹配统一按资源不可见处理。
     * 归属失败必须先于一切业务读写（模型调用/查库/状态保存/用量归集）。
     */
    public TravelState loadOwned(String sessionId, long actorId) {
        Optional<TravelState> state = load(sessionId);
        if (state.isEmpty() || state.get().getUserId() == null
                || state.get().getUserId() != actorId) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        return state.get();
    }

    /** 会话是否被持久化异步操作占用；阶段回退据此拒绝与在途提交并发写同一快照。 */
    public boolean hasActiveOperation(String sessionId) {
        if (!dbAuthoritative || sessionId == null) {
            return false;
        }
        try {
            TravelSessionState row = sessionStateMapper.selectById(sessionId);
            return row != null && row.getActiveOperationId() != null && !row.getActiveOperationId().isBlank();
        } catch (Exception e) {
            log.error("会话操作占用状态读取失败: {}", e.getMessage());
            throw new BizException(ResultCode.STORAGE_UNAVAILABLE);
        }
    }

    /**
     * 断点恢复：查找当前用户最近一条可恢复的未结束会话。
     * - 未结束 = stage 非 DONE，或存在进行中操作（重新生成/调整中）；
     * - 恢复窗口 = 最近活动（updated_at）在 resume-ttl 内且未超过 2 小时硬上限；
     * - 命中即滑动续期 expires_at（恢复视为活动，硬上限重新起算）；
     * - 不存在/已过期返回 empty；存储故障/数据损坏抛 STORAGE_UNAVAILABLE（不伪装成过期）。
     */
    public Optional<ResumeView> findResumable(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        TravelSessionState row;
        try {
            row = sessionStateMapper.selectLatestUnfinished(userId, resumeTtlMinutes());
        } catch (Exception e) {
            log.error("可恢复会话查询失败（DB 不可用）: {}", e.getMessage());
            throw new BizException(ResultCode.STORAGE_UNAVAILABLE);
        }
        if (row == null) {
            return Optional.empty();
        }
        return toResumeView(row);
    }

    /**
     * 按 sessionId 校验可恢复性（深链接恢复分支）：同一套「未结束 + 恢复窗口 + 硬上限」口径，
     * 归属不匹配、超窗或过期统一 empty；不存在也 empty（与 loadOwned 的 404 语义区分开：
     * 恢复入口只关心「还能不能继续」，前端据此提示过期）。
     */
    public Optional<ResumeView> findResumable(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        TravelSessionState row;
        try {
            row = sessionStateMapper.selectResumableById(userId, sessionId, resumeTtlMinutes());
        } catch (Exception e) {
            log.error("可恢复会话查询失败（DB 不可用）: {}", e.getMessage());
            throw new BizException(ResultCode.STORAGE_UNAVAILABLE);
        }
        if (row == null) {
            return Optional.empty();
        }
        return toResumeView(row);
    }

    /** TTL 毫秒 → SQL 窗口分钟（DB 时钟域，最小 1 分钟粒度） */
    private int resumeTtlMinutes() {
        long minutes = (resumeTtlMs + 59_999L) / 60_000L;
        return (int) Math.max(1L, Math.min(minutes, 10_080L));
    }

    /** 统一可恢复性判定 + ResumeView 组装 + 滑动续期（判定失败返回 empty，不伪装成过期） */
    private Optional<ResumeView> toResumeView(TravelSessionState row) {
        LocalDateTime now = LocalDateTime.now();
        boolean unfinished = !"DONE".equals(row.getStage())
                || (row.getActiveOperationId() != null && !row.getActiveOperationId().isBlank());
        if (!unfinished) {
            return Optional.empty();
        }
        // expires_at 由应用写入（应用时钟域），硬上限在应用时钟域比对
        if (row.getExpiresAt() != null && row.getExpiresAt().isBefore(now)) {
            return Optional.empty();
        }
        TravelState state;
        try {
            state = objectMapper.readValue(row.getStateJson(), TravelState.class);
        } catch (Exception e) {
            log.error("会话快照数据损坏，无法反序列化: {}", e.getMessage());
            throw new BizException(ResultCode.STORAGE_UNAVAILABLE);
        }
        ResumeView view = new ResumeView();
        view.setSessionId(row.getSessionId());
        view.setDestinationId(state.getDestinationId());
        view.setDestinationName(state.getDestinationName());
        view.setStage(state.getStage());
        view.setActiveOperationId(row.getActiveOperationId());
        // generating（同步生成进行中）为进程内语义，由编排层从 GenerationRegistry 回填
        // updated_at 由 DB 时钟写入（容器 UTC，与 NOW() 同域），按 UTC 还原真实时刻；
        // 与 expires_at（应用本地时钟域）分属两个时钟域，各自在写入域内比较
        view.setUpdatedAt(row.getUpdatedAt() == null ? null
                : row.getUpdatedAt().toInstant(java.time.ZoneOffset.UTC).toEpochMilli());
        // 滑动续期：只动 expires_at（updated_at 由 ON UPDATE 自动滑动 = 恢复即活动）
        LocalDateTime renewed = now.plusHours(2);
        if (row.getExpiresAt() == null || row.getExpiresAt().isBefore(renewed)) {
            try {
                TravelSessionState touch = new TravelSessionState();
                touch.setSessionId(row.getSessionId());
                touch.setExpiresAt(renewed);
                sessionStateMapper.updateById(touch);
            } catch (Exception e) {
                log.warn("恢复会话续期失败（非关键，下次仍按恢复窗口判定）: {}", e.getMessage());
            }
        }
        return Optional.of(view);
    }
}
