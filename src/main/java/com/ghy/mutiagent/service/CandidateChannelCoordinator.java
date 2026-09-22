package com.ghy.mutiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.model.FoodCandidate;
import com.ghy.mutiagent.model.HotelCandidate;
import com.ghy.mutiagent.model.WebFoodCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 候选通道协调器（三通道并行预热，功能隔离）：
 * 各通道（景点/美食/酒店）在后台并行生成后，把结果与就绪标记写入 Redis（按会话×通道）。
 * 前端/编排层命中缓存即视为「面板已好」；未命中回退到既有的同步懒加载路径（旧行为）。
 * 写缓存而不是写会话状态：避免后台线程与用户操作并发写 state 互相覆盖。
 * Redis 不可用时全部方法静默降级（等价于功能关闭），不阻塞主流程。
 */
@Service
public class CandidateChannelCoordinator {

    public static final String CHANNEL_ATTRACTION = "ATTRACTION";
    public static final String CHANNEL_FOOD = "FOOD";
    public static final String CHANNEL_HOTEL = "HOTEL";
    public static final String STATUS_READY = "READY";
    /** 用户明确不需要该通道；它与 READY 一样是终态，但没有候选结果。 */
    public static final String STATUS_SKIPPED = "SKIPPED";

    private static final Logger log = LoggerFactory.getLogger(CandidateChannelCoordinator.class);
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final String READY_KEY = "travel:candidate:ready:%s:%s";
    private static final String RESULT_KEY = "travel:candidate:result:%s:%s";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public CandidateChannelCoordinator(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** 美食通道后台结果（从会话状态中提取的增量，应用时只覆盖美食相关字段） */
    public record FoodChannelResult(List<FoodCandidate> foodPool, int foodCursor, String advice,
                                    List<WebFoodCandidate> webFoodCandidates,
                                    Map<String, com.ghy.mutiagent.model.CandidateSnapshot> snapshots) {
    }

    /** 酒店通道后台结果 */
    public record HotelChannelResult(List<HotelCandidate> hotelPool, int hotelCursor, String advice,
                                     Map<String, com.ghy.mutiagent.model.CandidateSnapshot> snapshots) {
    }

    /** 标记通道就绪（可选时间值：RUNNING 用「进行中」语义由调用方传） */
    public void markReady(String sessionId, String channel, String status) {
        if (redis == null || sessionId == null || channel == null) {
            return;
        }
        try {
            redis.opsForValue().set(String.format(READY_KEY, sessionId, channel), status, TTL);
        } catch (Exception e) {
            log.warn("通道就绪标记写入失败（{}），降级为懒加载", e.getClass().getSimpleName());
        }
    }

    /** 查询会话全部通道的就绪状态（未标记的通道不在 map 中 → 前端视为未就绪） */
    public Map<String, String> ready(String sessionId) {
        Map<String, String> out = new LinkedHashMap<>();
        if (redis == null || sessionId == null) {
            return out;
        }
        for (String c : List.of(CHANNEL_ATTRACTION, CHANNEL_FOOD, CHANNEL_HOTEL)) {
            try {
                String v = redis.opsForValue().get(String.format(READY_KEY, sessionId, c));
                if (v != null && !v.isBlank()) {
                    out.put(c, v);
                }
            } catch (Exception ignored) {
                // 单个 key 读取失败不阻断其他通道
            }
        }
        return out;
    }

    /** 写入通道结果（幂等覆盖；TTL 与就绪标记一致） */
    public void putResult(String sessionId, String channel, Object payload) {
        if (redis == null || sessionId == null || channel == null || payload == null) {
            return;
        }
        try {
            redis.opsForValue().set(String.format(RESULT_KEY, sessionId, channel),
                    objectMapper.writeValueAsString(payload), TTL);
        } catch (Exception e) {
            log.warn("通道结果缓存写入失败（{}），降级为懒加载", e.getClass().getSimpleName());
        }
    }

    /** 读取通道结果；未命中返回 null（调用方走同步懒加载） */
    public <T> T getResult(String sessionId, String channel, Class<T> type) {
        if (redis == null || sessionId == null || channel == null) {
            return null;
        }
        try {
            String v = redis.opsForValue().get(String.format(RESULT_KEY, sessionId, channel));
            if (v == null || v.isBlank()) {
                return null;
            }
            return objectMapper.readValue(v, type);
        } catch (Exception e) {
            log.warn("通道结果缓存读取失败（{}），降级为懒加载", e.getClass().getSimpleName());
            return null;
        }
    }

    /** 清空会话全部通道缓存与就绪标记（全局需求变更时调用） */
    public void clear(String sessionId) {
        if (redis == null || sessionId == null) {
            return;
        }
        try {
            for (String c : List.of(CHANNEL_ATTRACTION, CHANNEL_FOOD, CHANNEL_HOTEL)) {
                redis.delete(String.format(READY_KEY, sessionId, c));
                redis.delete(String.format(RESULT_KEY, sessionId, c));
            }
        } catch (Exception ignored) {
            // 清理失败等价于旧结果残留：TTL 兜底过期
        }
    }
}
