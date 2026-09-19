package com.ghy.mutiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.model.HistoryItem;
import com.ghy.mutiagent.model.ResumeView;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.model.enums.TravelStage;
import com.ghy.mutiagent.repository.entity.TravelSessionState;
import com.ghy.mutiagent.repository.mapper.TravelSessionStateMapper;
import com.ghy.mutiagent.repository.mapper.UsageRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 断点恢复契约测试：
 * - findResumable：命中映射字段 + 滑动续期；无行/硬上限过期/DONE → empty；
 *   存储故障/数据损坏 → STORAGE_UNAVAILABLE（不伪装成过期）；
 *   恢复窗口（updated_at）由 SQL 的 DB 时钟域过滤，此处校验 TTL 分钟透传；
 * - chatHistory：Q&A 回放过滤空消息；读失败降级为空历史不阻断恢复。
 */
@ExtendWith(MockitoExtension.class)
class ResumableSessionTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private TravelSessionStateMapper sessionStateMapper;

    @Mock
    private UsageRecordMapper usageRecordMapper;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private TravelSessionService sessionService;
    private UsageService usageService;

    @BeforeEach
    void setUp() {
        sessionService = new TravelSessionService(redis, objectMapper, sessionStateMapper, "db");
        usageService = new UsageService(usageRecordMapper, null);
    }

    private TravelSessionState row(String sessionId, String stage, String activeOp,
                                   LocalDateTime expiresAt, LocalDateTime updatedAt,
                                   Long destinationId, String destinationName) throws Exception {
        TravelState s = new TravelState();
        s.setSessionId(sessionId);
        s.setUserId(1L);
        s.setStage(TravelStage.valueOf(stage));
        s.setDestinationId(destinationId);
        s.setDestinationName(destinationName);
        TravelSessionState row = new TravelSessionState();
        row.setSessionId(sessionId);
        row.setUserId(1L);
        row.setStage(stage);
        row.setActiveOperationId(activeOp);
        row.setExpiresAt(expiresAt);
        row.setUpdatedAt(updatedAt);
        row.setStateJson(objectMapper.writeValueAsString(s));
        return row;
    }

    // ---------- findResumable(userId) 最新会话 ----------

    @Test
    void returnsViewWithFieldsAndSlidesExpiry() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(sessionStateMapper.selectLatestUnfinished(any(), anyInt()))
                .thenReturn(row("s1", "HOTELS", "op1", now.plusMinutes(10), now, 5L, "南京"));

        Optional<ResumeView> found = sessionService.findResumable(1L);

        assertThat(found).isPresent();
        ResumeView v = found.get();
        assertThat(v.getSessionId()).isEqualTo("s1");
        assertThat(v.getDestinationId()).isEqualTo(5L);
        assertThat(v.getDestinationName()).isEqualTo("南京");
        assertThat(v.getStage()).isEqualTo(TravelStage.HOTELS);
        assertThat(v.getActiveOperationId()).isEqualTo("op1");
        assertThat(v.getUpdatedAt()).isNotNull();
        // 默认恢复窗口 30 分钟，换算 SQL 分钟粒度透传
        verify(sessionStateMapper).selectLatestUnfinished(eq(1L), eq(30));
        // 滑动续期：expires_at 不足 2h 时延到 now+2h（updateById 有单/批量重载，用类型见证固定单条重载）
        java.util.concurrent.atomic.AtomicReference<TravelSessionState> touched =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(sessionStateMapper.updateById(ArgumentMatchers.<TravelSessionState>any()))
                .thenAnswer(inv -> {
                    touched.set(inv.getArgument(0));
                    return 1;
                });
        sessionService.findResumable(1L);
        assertThat(touched.get()).isNotNull();
        assertThat(touched.get().getSessionId()).isEqualTo("s1");
        assertThat(touched.get().getExpiresAt()).isAfter(now.plusMinutes(119));
    }

    @Test
    void doesNotTouchWhenExpiryAlreadyLongEnough() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(sessionStateMapper.selectLatestUnfinished(any(), anyInt()))
                .thenReturn(row("s1", "ATTRACTIONS", null, now.plusHours(5), now, 5L, "南京"));

        Optional<ResumeView> found = sessionService.findResumable(1L);

        assertThat(found).isPresent();
        verify(sessionStateMapper, never()).updateById(ArgumentMatchers.<TravelSessionState>any());
    }

    @Test
    void returnsEmptyWhenNoUnfinishedRow() {
        when(sessionStateMapper.selectLatestUnfinished(any(), anyInt())).thenReturn(null);

        assertThat(sessionService.findResumable(1L)).isEmpty();
    }

    @Test
    void returnsEmptyWhenHardCapExpired() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(sessionStateMapper.selectLatestUnfinished(any(), anyInt()))
                .thenReturn(row("s1", "FOODS", null, now.minusSeconds(1), now, 5L, "南京"));

        assertThat(sessionService.findResumable(1L)).isEmpty();
    }

    @Test
    void returnsEmptyForNullUser() {
        assertThat(sessionService.findResumable(null)).isEmpty();
    }

    @Test
    void throwsStorageUnavailableWhenMapperFails() {
        when(sessionStateMapper.selectLatestUnfinished(any(), anyInt()))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> sessionService.findResumable(1L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ResultCode.STORAGE_UNAVAILABLE.getCode());
    }

    @Test
    void throwsStorageUnavailableWhenSnapshotCorrupt() {
        TravelSessionState row = new TravelSessionState();
        row.setSessionId("s1");
        row.setUserId(1L);
        row.setStage("HOTELS");
        row.setExpiresAt(LocalDateTime.now().plusHours(1));
        row.setUpdatedAt(LocalDateTime.now());
        row.setStateJson("{broken json");
        when(sessionStateMapper.selectLatestUnfinished(any(), anyInt())).thenReturn(row);

        assertThatThrownBy(() -> sessionService.findResumable(1L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ResultCode.STORAGE_UNAVAILABLE.getCode());
    }

    // ---------- findResumable(userId, sessionId) 深链接分支 ----------

    @Test
    void byIdReturnsViewForOwnUnfinishedSession() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(sessionStateMapper.selectResumableById(any(), any(), anyInt()))
                .thenReturn(row("s1", "ATTRACTIONS", null, now.plusHours(1), now, 5L, "南京"));

        Optional<ResumeView> found = sessionService.findResumable(1L, "s1");

        assertThat(found).isPresent();
        assertThat(found.get().getSessionId()).isEqualTo("s1");
        assertThat(found.get().getStage()).isEqualTo(TravelStage.ATTRACTIONS);
        // 归属与 TTL 以 SQL 参数透传（DB 时钟域过滤）
        verify(sessionStateMapper).selectResumableById(eq(1L), eq("s1"), eq(30));
    }

    @Test
    void byIdPassesActorThroughAndReturnsEmptyForMissing() {
        when(sessionStateMapper.selectResumableById(eq(2L), eq("s1"), anyInt())).thenReturn(null);

        assertThat(sessionService.findResumable(2L, "s1")).isEmpty();
    }

    @Test
    void byIdReturnsEmptyWhenSessionFinished() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(sessionStateMapper.selectResumableById(any(), any(), anyInt()))
                .thenReturn(row("s1", "DONE", null, now.plusHours(1), now, 5L, "南京"));

        assertThat(sessionService.findResumable(1L, "s1")).isEmpty();
    }

    @Test
    void byIdReturnsEmptyForMissingRow() {
        when(sessionStateMapper.selectResumableById(any(), any(), anyInt())).thenReturn(null);

        assertThat(sessionService.findResumable(1L, "s1")).isEmpty();
    }

    @Test
    void byIdReturnsEmptyForNullArgs() {
        assertThat(sessionService.findResumable(null, "s1")).isEmpty();
        assertThat(sessionService.findResumable(1L, null)).isEmpty();
        assertThat(sessionService.findResumable(1L, " ")).isEmpty();
    }

    // ---------- chatHistory ----------

    @Test
    void buildsUserAssistantPairsFromQaRows() {
        when(usageRecordMapper.selectQaHistory("s1", 200)).thenReturn(List.of(
                Map.of("question", "想去海边", "answer", "好的，为你推荐海边城市"),
                Map.of("question", "三天两晚", "answer", "")));

        List<HistoryItem> items = usageService.chatHistory("s1", 500);

        assertThat(items).hasSize(3);
        assertThat(items.get(0).getRole()).isEqualTo("user");
        assertThat(items.get(0).getText()).isEqualTo("想去海边");
        assertThat(items.get(1).getRole()).isEqualTo("assistant");
        assertThat(items.get(1).getText()).isEqualTo("好的，为你推荐海边城市");
        assertThat(items.get(2).getRole()).isEqualTo("user");
        assertThat(items.get(2).getText()).isEqualTo("三天两晚");
    }

    @Test
    void skipsBlankMessages() {
        when(usageRecordMapper.selectQaHistory("s1", 200)).thenReturn(List.of(
                Map.of("question", " ", "answer", "空问题不回放"),
                Map.of("question", "正常", "answer", "正常回复")));

        List<HistoryItem> items = usageService.chatHistory("s1", 200);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).getText()).isEqualTo("正常");
        assertThat(items.get(1).getText()).isEqualTo("正常回复");
    }

    @Test
    void degradesToEmptyHistoryOnReadFailure() {
        when(usageRecordMapper.selectQaHistory("s1", 200))
                .thenThrow(new RuntimeException("db down"));

        assertThat(usageService.chatHistory("s1", 200)).isEmpty();
    }

    @Test
    void returnsEmptyForBlankSession() {
        assertThat(usageService.chatHistory(null, 200)).isEmpty();
        assertThat(usageService.chatHistory("  ", 200)).isEmpty();
    }
}
