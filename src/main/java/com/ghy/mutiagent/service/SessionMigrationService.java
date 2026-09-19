package com.ghy.mutiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.repository.entity.TravelSessionState;
import com.ghy.mutiagent.repository.mapper.TravelSessionStateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 旧 Redis 会话迁移（S06-B，手册§11.2）：维护窗口期的一次性导入工具，不在普通请求路径自动认领。
 *
 * - 读取 owner/TTL/完整 JSON 并校验：owner 缺失（历史匿名数据）不认领，跳过并报告；
 * - TTL 已过期或 JSON 损坏跳过；
 * - 校验通过后一次性导入 DB 快照（revision=0、schemaVersion=2），已存在则跳过不覆盖；
 * - 旧行程保持原样可读（legacy），不静默重算或覆盖历史账单。
 */
@Service
public class SessionMigrationService {

    private static final Logger log = LoggerFactory.getLogger(SessionMigrationService.class);
    private static final String LEGACY_KEY_PREFIX = "travel:session:";

    public record MigrationReport(int migrated, List<String> skippedNoOwner, List<String> skippedExpired) {
    }

    private final TravelSessionStateMapper sessionStateMapper;
    private final ObjectMapper objectMapper;

    public SessionMigrationService(TravelSessionStateMapper sessionStateMapper, ObjectMapper objectMapper) {
        this.sessionStateMapper = sessionStateMapper;
        this.objectMapper = objectMapper;
    }

    /** 迁移指定的旧会话键（调用方提供 Redis 扫描结果；本方法负责读取/校验/导入） */
    public MigrationReport migrateFromRedis(StringRedisTemplate redis, Collection<String> legacySessionIds) {
        List<String> noOwner = new ArrayList<>();
        List<String> expired = new ArrayList<>();
        int migrated = 0;
        for (String sessionId : legacySessionIds) {
            String key = LEGACY_KEY_PREFIX + sessionId;
            String json;
            Long ttlSeconds;
            try {
                json = redis.opsForValue().get(key);
                ttlSeconds = redis.getExpire(key);
            } catch (Exception e) {
                log.warn("[Migration] 旧会话 {} 读取失败，跳过: {}", sessionId, e.getMessage());
                expired.add(sessionId);
                continue;
            }
            if (json == null || json.isBlank() || (ttlSeconds != null && ttlSeconds <= 0)) {
                expired.add(sessionId); // 已过期/不存在：不迁移
                continue;
            }
            TravelState state;
            try {
                state = objectMapper.readValue(json, TravelState.class);
            } catch (Exception e) {
                log.warn("[Migration] 旧会话 {} JSON 损坏，跳过", sessionId);
                expired.add(sessionId);
                continue;
            }
            if (state.getUserId() == null) {
                noOwner.add(sessionId); // 未知 owner：不得认领
                continue;
            }
            if (sessionStateMapper.selectById(sessionId) != null) {
                continue; // 已存在（新版本已写过）：不覆盖
            }
            TravelSessionState row = new TravelSessionState();
            row.setSessionId(sessionId);
            row.setUserId(state.getUserId());
            row.setRevision(0L);
            row.setSchemaVersion(2);
            row.setStage(state.getStage() == null ? "" : state.getStage().name());
            row.setStateJson(json);
            row.setExpiresAt(LocalDateTime.now().plus(Duration.ofHours(2)));
            try {
                sessionStateMapper.insert(row);
                migrated++;
                log.info("[Migration] 旧会话 {} 已导入 DB 快照（owner={}）", sessionId, state.getUserId());
            } catch (Exception e) {
                log.warn("[Migration] 旧会话 {} 导入失败，跳过: {}", sessionId, e.getMessage());
            }
        }
        return new MigrationReport(migrated, noOwner, expired);
    }
}
