package com.ghy.mutiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.model.TravelState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * P0-S01 契约测试：会话归属加载 loadOwned。
 * - 不存在 / 无所有者记录 / 所有者不匹配 → 统一 404（资源不可见，防匿名探测）；
 * - Redis 故障 / 数据损坏 → 500（系统错误，不得伪装成「会话不存在」）。
 */
@ExtendWith(MockitoExtension.class)
class TravelSessionServiceOwnershipTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private TravelSessionService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        // 本组用例验证 redis-authoritative（S06-A）语义；DB 权威分支由官方门 live 驱动验证
        service = new TravelSessionService(redis, objectMapper, null, "redis");
    }

    private String jsonOf(TravelState s) throws Exception {
        return objectMapper.writeValueAsString(s);
    }

    @Test
    void shouldReturnStateWhenOwnerMatches() throws Exception {
        TravelState s = new TravelState();
        s.setSessionId("s1");
        s.setUserId(1L);
        when(valueOps.get("travel:session:s1")).thenReturn(jsonOf(s));

        TravelState loaded = service.loadOwned("s1", 1L);
        assertThat(loaded.getSessionId()).isEqualTo("s1");
        assertThat(loaded.getUserId()).isEqualTo(1L);
    }

    @Test
    void shouldHideMissingSessionAs404() {
        when(valueOps.get("travel:session:s1")).thenReturn(null);
        assertThatThrownBy(() -> service.loadOwned("s1", 1L))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.RESOURCE_NOT_FOUND.getCode()));
    }

    @Test
    void shouldHideOwnerlessSessionAs404() throws Exception {
        TravelState s = new TravelState();
        s.setSessionId("s1"); // userId 保持 null（历史匿名数据）
        when(valueOps.get("travel:session:s1")).thenReturn(jsonOf(s));
        assertThatThrownBy(() -> service.loadOwned("s1", 1L))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.RESOURCE_NOT_FOUND.getCode()));
    }

    @Test
    void shouldHideForeignSessionAs404() throws Exception {
        TravelState s = new TravelState();
        s.setSessionId("s1");
        s.setUserId(2L);
        when(valueOps.get("travel:session:s1")).thenReturn(jsonOf(s));
        assertThatThrownBy(() -> service.loadOwned("s1", 1L))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.RESOURCE_NOT_FOUND.getCode()));
    }

    @Test
    void shouldSurfaceRedisFailureAsSystemErrorNot404() {
        when(valueOps.get("travel:session:s1")).thenThrow(new RuntimeException("redis down"));
        assertThatThrownBy(() -> service.loadOwned("s1", 1L))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.SYSTEM_ERROR.getCode()));
    }

    @Test
    void shouldSurfaceCorruptedDataAsSystemErrorNot404() {
        when(valueOps.get("travel:session:s1")).thenReturn("{{{ 不是合法 json");
        assertThatThrownBy(() -> service.loadOwned("s1", 1L))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.SYSTEM_ERROR.getCode()));
    }

    /** P0-S06-A：权威保存失败必须可见（向上抛系统错误），不得只打日志伪装成功 */
    @Test
    void shouldSurfaceSaveFailureAsSystemError() {
        TravelState s = new TravelState();
        s.setSessionId("s1");
        s.setUserId(1L);
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(valueOps).set(org.mockito.ArgumentMatchers.eq("travel:session:s1"),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(java.time.Duration.class));
        assertThatThrownBy(() -> service.save(s))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.SYSTEM_ERROR.getCode()));
    }
}
