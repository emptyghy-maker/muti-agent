package com.ghy.mutiagent.service.orch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.agent.PreferenceAgent;
import com.ghy.mutiagent.agent.RequirementAgent;
import com.ghy.mutiagent.model.AttractionCandidate;
import com.ghy.mutiagent.model.ChatStepResult;
import com.ghy.mutiagent.model.FoodCandidate;
import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.model.enums.TravelStage;
import com.ghy.mutiagent.repository.mapper.DestinationMapper;
import com.ghy.mutiagent.rule.RulePreferenceParser;
import com.ghy.mutiagent.security.AuthenticatedUser;
import com.ghy.mutiagent.service.CandidateChannelCoordinator;
import com.ghy.mutiagent.service.CandidateService;
import com.ghy.mutiagent.service.GenerationRegistry;
import com.ghy.mutiagent.service.ItineraryService;
import com.ghy.mutiagent.service.TravelOperationService;
import com.ghy.mutiagent.service.TravelSessionService;
import com.ghy.mutiagent.service.UsageService;
import com.ghy.mutiagent.trace.TraceContext;
import com.ghy.mutiagent.trace.TraceService;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 候选通道并行预热（功能隔离 + 可回退开关）：
 * - 开启：偏好收集完成后美食/酒店通道与景点重筛并行预热（结果写缓存），
 *   确认进入美食/酒店环节时缓存命中 → 不再同步生成（零等待）；
 * - 关闭（默认/手动装配/门禁）：确认环节同步懒加载（既有行为完全不变）。
 */
@ExtendWith(MockitoExtension.class)
class CandidateChannelPreWarmTest {

    @Mock
    private TravelSessionService sessionService;
    @Mock
    private DestinationMapper destinationMapper;
    @Mock
    private RulePreferenceParser rulePreferenceParser;
    @Mock
    private PreferenceAgent preferenceAgent;
    @Mock
    private RequirementAgent requirementAgent;
    @Mock
    private CandidateService candidateService;
    @Mock
    private ItineraryService itineraryService;
    @Mock
    private UsageService usageService;
    @Mock
    private TraceService traceService;
    @Mock
    private TravelOperationService operationService;
    @Mock
    private CandidateChannelCoordinator coordinator;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TaskExecutor taskExecutor = Runnable::run; // 同步执行：预热任务内联跑完，便于断言

    private TravelOrchestrator orchestrator;
    private AuthenticatedUser actor;

    @BeforeEach
    void setUp() {
        orchestrator = new TravelOrchestrator(sessionService, destinationMapper, rulePreferenceParser,
                preferenceAgent, requirementAgent, candidateService, itineraryService, usageService,
                traceService, objectMapper, taskExecutor, operationService);
        orchestrator.generationRegistry = new GenerationRegistry();
        org.mockito.Mockito.lenient().when(traceService.newTrace(anyString(), anyString()))
                .thenAnswer(inv -> new TraceContext(inv.getArgument(0, String.class),
                        inv.getArgument(1, String.class)));
        org.mockito.Mockito.lenient().when(requirementAgent.analyze(anyString())).thenReturn(
                Result.<String>builder().content(
                        "{\"mode\":\"agent\",\"focus\":[\"情侣约会\"],\"brief\":\"围绕约会定制。\","
                                + "\"needs\":{\"path\":3,\"cost\":2,\"sightseeing\":4,\"food\":5},"
                                + "\"tags\":[\"浪漫\",\"夜景\"]}").build());
        // 模拟真实的批次重建：缓存应用后 nextFoodBatch 从池中切出首批（回归：验证编排层确实调用）
        // lenient：关闭并行的用例不会走到批次重建
        org.mockito.Mockito.lenient().doAnswer(inv -> {
            TravelState st = inv.getArgument(0);
            List<FoodCandidate.FoodItem> items = st.getFoodPool() == null ? List.of()
                    : st.getFoodPool().stream().flatMap(g -> g.getRestaurants().stream()).toList();
            FoodCandidate batch = new FoodCandidate();
            batch.setRestaurants(items);
            st.setFoodCandidates(List.of(batch));
            return 0;
        }).when(candidateService).nextFoodBatch(anyStateArg());
        org.mockito.Mockito.lenient().when(coordinator.putResult(anyString(), anyString(), any()))
                .thenReturn(true);
        actor = new AuthenticatedUser(1L, "admin", "ADMIN");
    }

    private TravelState state() {
        TravelState s = new TravelState();
        s.setSessionId("s-pre");
        s.setUserId(1L);
        s.setUsername("admin");
        s.setStage(TravelStage.PREFERENCE);
        s.setDestinationId(1L);
        s.setDestinationName("南京");
        TravelPreference p = new TravelPreference();
        p.setDays(1);
        p.setTotalBudget(BigDecimal.valueOf(600));
        p.setPeopleCount(2);
        p.setAttractionType("混合");
        p.setFoodTaste("本地特色菜");
        p.setEnergyLevel("一般");
        p.setHotelStyle("性价比优先");
        p.setSpecialRequests("情侣约会");
        s.setPreference(p);
        AttractionCandidate a = new AttractionCandidate();
        a.setAttractionId(1L);
        a.setName("夫子庙秦淮风光带");
        s.setAttractionPool(List.of(a));
        return s;
    }

    private void enableParallel() {
        ReflectionTestUtils.setField(orchestrator, "parallelCandidates", Boolean.TRUE);
        ReflectionTestUtils.setField(orchestrator, "channelCoordinator", coordinator);
    }

    @Test
    void 开启后偏好完成即并行预热且确认环节缓存命中零生成() {
        enableParallel();
        TravelState s = state();
        when(sessionService.loadOwned("s-pre", 1L)).thenReturn(s);
        FoodCandidate cachedGroup = new FoodCandidate();
        FoodCandidate.FoodItem cachedItem = new FoodCandidate.FoodItem();
        cachedItem.setRestaurantId(9L);
        cachedItem.setName("测试餐厅");
        cachedGroup.setRestaurants(List.of(cachedItem));
        when(coordinator.getResult(eq("s-pre"), eq(CandidateChannelCoordinator.CHANNEL_FOOD),
                eq(CandidateChannelCoordinator.FoodChannelResult.class)))
                .thenReturn(new CandidateChannelCoordinator.FoodChannelResult(
                        List.of(cachedGroup), 8, "advice", List.of(), Map.of()));

        // 偏好收集完成：景点生成 + 美食/酒店预热（同步执行器内联完成）
        ChatStepResult r = (ChatStepResult) ReflectionTestUtils.invokeMethod(orchestrator,
                "doCompletePreference", s);
        assertThat(r.getStage()).isEqualTo(TravelStage.ATTRACTIONS);
        verify(candidateService, times(1)).generateFoods(anyStateArg());
        // 预热各一次（景点 mock 不计），就绪标记：ATTRACTION READY + FOOD/HOTEL READY
        verify(candidateService, times(1)).generateHotels(anyStateArg());
        verify(coordinator).markReady(eq("s-pre"), eq(CandidateChannelCoordinator.CHANNEL_ATTRACTION), eq("READY"));
        verify(coordinator).putResult(eq("s-pre"), eq(CandidateChannelCoordinator.CHANNEL_FOOD),
                org.mockito.ArgumentMatchers.any());
        verify(coordinator).putResult(eq("s-pre"), eq(CandidateChannelCoordinator.CHANNEL_HOTEL),
                org.mockito.ArgumentMatchers.any());

        // 确认景点 → 美食环节：缓存命中，不再调用 generateFoods（仍只有预热那一次）
        ChatStepResult confirm = orchestrator.confirmCandidates(actor, "s-pre", "ATTRACTION", List.of(1L), false);
        assertThat(confirm.getStage()).isEqualTo(TravelStage.FOODS);
        assertThat(s.getFoodPool()).hasSize(1); // 缓存结果已应用到会话
        verify(candidateService, times(1)).generateFoods(anyStateArg());
        // 回归：缓存不含展示批次，应用后必须按游标重建（否则前端拿到的 foodCandidates 为空，页面无候选）
        assertThat(s.getFoodCandidates()).flatExtracting(FoodCandidate::getRestaurants).hasSize(1);
        assertThat(s.getFoodCandidates().get(0).getRestaurants().get(0).getName()).isEqualTo("测试餐厅");
    }

    @Test
    void 关闭时保持既有串行懒加载行为() {
        // 默认不设置开关（手动装配/门禁同构）：偏好完成不预热
        TravelState s = state();
        when(sessionService.loadOwned("s-pre", 1L)).thenReturn(s);

        ReflectionTestUtils.invokeMethod(orchestrator, "doCompletePreference", s);

        verify(candidateService, never()).generateFoods(anyStateArg());
        verify(candidateService, never()).generateHotels(anyStateArg());
        verify(coordinator, never()).putResult(anyString(), anyString(), org.mockito.ArgumentMatchers.any());

        // 确认景点 → 美食环节：同步懒加载（既有行为）
        orchestrator.confirmCandidates(actor, "s-pre", "ATTRACTION", List.of(1L), false);
        verify(candidateService, times(1)).generateFoods(anyStateArg());
    }

    @Test
    void 不需要酒店时只预热美食并把酒店标记为跳过() {
        enableParallel();
        TravelState s = state();
        s.setNoHotelNeeded(true);

        ReflectionTestUtils.invokeMethod(orchestrator, "doCompletePreference", s);

        verify(candidateService, times(1)).generateFoods(anyStateArg());
        verify(candidateService, never()).generateHotels(anyStateArg());
        verify(coordinator).markReady(eq("s-pre"), eq(CandidateChannelCoordinator.CHANNEL_FOOD),
                eq(CandidateChannelCoordinator.STATUS_READY));
        verify(coordinator).markReady(eq("s-pre"), eq(CandidateChannelCoordinator.CHANNEL_HOTEL),
                eq(CandidateChannelCoordinator.STATUS_SKIPPED));
        verify(coordinator, never()).putResult(eq("s-pre"), eq(CandidateChannelCoordinator.CHANNEL_HOTEL),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 不需要美食时只预热酒店并把美食标记为跳过() {
        enableParallel();
        TravelState s = state();
        s.setNoFoodNeeded(true);

        ReflectionTestUtils.invokeMethod(orchestrator, "doCompletePreference", s);

        verify(candidateService, never()).generateFoods(anyStateArg());
        verify(candidateService, times(1)).generateHotels(anyStateArg());
        verify(coordinator).markReady(eq("s-pre"), eq(CandidateChannelCoordinator.CHANNEL_FOOD),
                eq(CandidateChannelCoordinator.STATUS_SKIPPED));
        verify(coordinator).markReady(eq("s-pre"), eq(CandidateChannelCoordinator.CHANNEL_HOTEL),
                eq(CandidateChannelCoordinator.STATUS_READY));
        verify(coordinator, never()).putResult(eq("s-pre"), eq(CandidateChannelCoordinator.CHANNEL_FOOD),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 预热结果写缓存失败时不得对前端声明已就绪() {
        enableParallel();
        TravelState s = state();
        when(coordinator.putResult(eq("s-pre"), eq(CandidateChannelCoordinator.CHANNEL_FOOD), any()))
                .thenReturn(false);

        ReflectionTestUtils.invokeMethod(orchestrator, "doCompletePreference", s);

        verify(coordinator).markReady(eq("s-pre"), eq(CandidateChannelCoordinator.CHANNEL_FOOD),
                eq(CandidateChannelCoordinator.STATUS_FALLBACK));
        verify(coordinator, never()).markReady(eq("s-pre"), eq(CandidateChannelCoordinator.CHANNEL_FOOD),
                eq(CandidateChannelCoordinator.STATUS_READY));
    }

    @Test
    void 美食缓存载荷损坏时确认景点应同步重建而不是系统错误() {
        enableParallel();
        TravelState s = state();
        s.setStage(TravelStage.ATTRACTIONS);
        when(sessionService.loadOwned("s-pre", 1L)).thenReturn(s);
        when(coordinator.getResult(eq("s-pre"), eq(CandidateChannelCoordinator.CHANNEL_FOOD),
                eq(CandidateChannelCoordinator.FoodChannelResult.class)))
                .thenReturn(new CandidateChannelCoordinator.FoodChannelResult(
                        null, 0, null, null, null));

        ChatStepResult result = orchestrator.confirmCandidates(
                actor, "s-pre", "ATTRACTION", List.of(1L), false);

        assertThat(result.getStage()).isEqualTo(TravelStage.FOODS);
        verify(candidateService).generateFoods(s);
        verify(coordinator).markReady("s-pre", CandidateChannelCoordinator.CHANNEL_FOOD,
                CandidateChannelCoordinator.STATUS_FALLBACK);
    }

    @Test
    void 旧会话只有就绪标记却没有美食结果时应纠正假就绪并同步重建() {
        enableParallel();
        TravelState s = state();
        s.setStage(TravelStage.ATTRACTIONS);
        when(sessionService.loadOwned("s-pre", 1L)).thenReturn(s);
        when(coordinator.ready("s-pre")).thenReturn(Map.of(
                CandidateChannelCoordinator.CHANNEL_FOOD, CandidateChannelCoordinator.STATUS_READY));

        ChatStepResult result = orchestrator.confirmCandidates(
                actor, "s-pre", "ATTRACTION", List.of(1L), false);

        assertThat(result.getStage()).isEqualTo(TravelStage.FOODS);
        verify(candidateService).generateFoods(s);
        verify(coordinator).markReady("s-pre", CandidateChannelCoordinator.CHANNEL_FOOD,
                CandidateChannelCoordinator.STATUS_FALLBACK);
    }

    private static TravelState anyStateArg() {
        return org.mockito.ArgumentMatchers.any(TravelState.class);
    }
}
