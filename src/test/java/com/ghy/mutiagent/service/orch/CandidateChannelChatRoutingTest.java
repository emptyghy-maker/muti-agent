package com.ghy.mutiagent.service.orch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.agent.PreferenceAgent;
import com.ghy.mutiagent.agent.RequirementAgent;
import com.ghy.mutiagent.model.AttractionCandidate;
import com.ghy.mutiagent.model.ChatStepResult;
import com.ghy.mutiagent.model.LocationConstraint;
import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.model.enums.TravelStage;
import com.ghy.mutiagent.repository.mapper.DestinationMapper;
import com.ghy.mutiagent.rule.RuleParseResult;
import com.ghy.mutiagent.rule.RulePreferenceParser;
import com.ghy.mutiagent.service.CandidateChannelCoordinator;
import com.ghy.mutiagent.service.CandidateService;
import com.ghy.mutiagent.service.GenerationRegistry;
import com.ghy.mutiagent.service.ItineraryService;
import com.ghy.mutiagent.service.TravelOperationService;
import com.ghy.mutiagent.service.TravelSessionService;
import com.ghy.mutiagent.service.UsageService;
import com.ghy.mutiagent.trace.TraceContext;
import com.ghy.mutiagent.trace.TraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 三通道独立对话（阶段4）：候选环节的自由文本经确定性路由到目标通道单独重筛
 * （latest-wins：其余通道不动），请求快照按通道登记互不污染；
 * 并行开启时同步重筛结果写回该通道缓存，确认环节不再命中预热旧结果。
 */
@ExtendWith(MockitoExtension.class)
class CandidateChannelChatRoutingTest {

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
    private final TaskExecutor taskExecutor = Runnable::run;

    private TravelOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new TravelOrchestrator(sessionService, destinationMapper, rulePreferenceParser,
                preferenceAgent, requirementAgent, candidateService, itineraryService, usageService,
                traceService, objectMapper, taskExecutor, operationService);
        orchestrator.generationRegistry = new GenerationRegistry();
        // 本测试 candidateService 为 mock（不产生真实 AI 调用），trace stub 仅兜底，用 lenient 标记
        lenient().when(traceService.newTrace(anyString(), anyString()))
                .thenAnswer(inv -> new TraceContext(inv.getArgument(0, String.class),
                        inv.getArgument(1, String.class)));
        // 规则解析零命中：消息作为未解析残余进入快照，由路由决定重筛通道
        when(rulePreferenceParser.parseResult(anyString(), isNull(), any())).thenReturn(new RuleParseResult());
        // 每个用例只重筛一个通道：其余通道的 stub 用 lenient 标记（严格模式下未用即报错）
        lenient().when(candidateService.generateAttractions(any())).thenReturn(true);
        lenient().when(candidateService.generateFoods(any())).thenReturn(true);
        lenient().when(candidateService.generateHotels(any())).thenReturn(true);
    }

    private TravelState state(TravelStage stage) {
        TravelState s = new TravelState();
        s.setSessionId("s-route");
        s.setUserId(1L);
        s.setUsername("admin");
        s.setStage(stage);
        s.setDestinationId(1L);
        s.setDestinationName("南京");
        TravelPreference p = new TravelPreference();
        p.setDays(1);
        p.setTotalBudget(BigDecimal.valueOf(600));
        p.setPeopleCount(2);
        s.setPreference(p);
        return s;
    }

    private ChatStepResult refine(TravelState s, String message) {
        return (ChatStepResult) ReflectionTestUtils.invokeMethod(orchestrator,
                "chatCandidateRefine", s, message);
    }

    @Test
    void 美食环节说酒店需求只重筛酒店通道() {
        TravelState s = state(TravelStage.FOODS);

        ChatStepResult r = refine(s, "酒店要便宜一点的");

        // 路由到酒店通道单独重筛：美食通道不动（保留预热/既有结果）
        verify(candidateService, times(1)).generateHotels(any(TravelState.class));
        verify(candidateService, never()).generateFoods(any(TravelState.class));
        verify(candidateService, never()).generateAttractions(any(TravelState.class));
        // 通道请求快照按通道登记（latest-wins）
        assertThat(s.channelRequestOf(CandidateChannelCoordinator.CHANNEL_HOTEL)).isNotNull();
        assertThat(s.channelRequestOf(CandidateChannelCoordinator.CHANNEL_FOOD)).isNull();
        // 回复明示是酒店通道被单独更新，且不影响当前环节
        assertThat(r.getMessage()).contains("酒店");
        assertThat(r.getMessage()).contains("单独更新");
    }

    @Test
    void 无通道关键词沿用当前环节且登记当前通道快照() {
        TravelState s = state(TravelStage.FOODS);

        refine(s, "预算控制在1000以内");

        verify(candidateService, times(1)).generateFoods(any(TravelState.class));
        verify(candidateService, never()).generateHotels(any(TravelState.class));
        assertThat(s.channelRequestOf(CandidateChannelCoordinator.CHANNEL_FOOD)).isNotNull();
    }

    @Test
    void 被跳过的通道不再重筛() {
        TravelState s = state(TravelStage.FOODS);
        s.setNoHotelNeeded(true);

        refine(s, "酒店要便宜一点的");

        // 用户已明确不要酒店：路由命中酒店也退回当前环节（美食），不为跳过通道浪费重筛
        verify(candidateService, times(1)).generateFoods(any(TravelState.class));
        verify(candidateService, never()).generateHotels(any(TravelState.class));
    }

    @Test
    void 并行开启时同步重筛结果写回通道缓存() {
        ReflectionTestUtils.setField(orchestrator, "parallelCandidates", Boolean.TRUE);
        ReflectionTestUtils.setField(orchestrator, "channelCoordinator", coordinator);
        TravelState s = state(TravelStage.FOODS);

        refine(s, "酒店要便宜一点的");

        // 覆盖预热旧结果：确认环节命中缓存拿到的是本次最新酒店池
        verify(coordinator).putResult(eq("s-route"), eq(CandidateChannelCoordinator.CHANNEL_HOTEL),
                any(CandidateChannelCoordinator.HotelChannelResult.class));
    }

    @Test
    void 景点太少触发扩充而不是把反馈写成旅行需求() {
        TravelState s = state(TravelStage.ATTRACTIONS);
        s.setExtraRequest("三牌楼附近的网红打卡景点");
        s.setWebAttractionSearchKey("confirm:old");
        LocationConstraint location = new LocationConstraint();
        location.setAnchorName("南京邮电大学三牌楼校区");
        location.setLng(118.770844);
        location.setLat(32.081113);
        location.setRadiusKm(3);
        location.setStatus(LocationConstraint.RESOLVED);
        location.setScopes(List.of("ATTRACTION"));
        s.setLocationConstraint(location);
        s.setAttractionPool(List.of(attractionCandidate(1), attractionCandidate(2), attractionCandidate(3)));

        ChatStepResult result = refine(s, "景点太少了，我怎么挑选？");

        verify(candidateService).generateAttractions(s);
        verify(candidateService, never()).generateFoods(any());
        assertThat(s.getWebAttractionSearchKey()).isNull();
        assertThat(s.channelRequestOf(CandidateChannelCoordinator.CHANNEL_ATTRACTION))
                .isEqualTo("三牌楼附近的网红打卡景点");
        assertThat(result.getMessage()).contains("只有 3 个", "扩大到5公里");
        assertThat(s.getRequirementSnapshot()).isNull();
    }

    @Test
    void 用户确认扩大到五公里会更新既有地点半径并重筛() {
        TravelState s = state(TravelStage.ATTRACTIONS);
        s.setExtraRequest("南京邮电大学三牌楼校区附近的网红景点");

        refine(s, "扩大到5公里");

        verify(candidateService).generateAttractions(s);
        assertThat(s.getLocationConstraint()).isNotNull();
        assertThat(s.getLocationConstraint().getAnchorName()).isEqualTo("南京邮电大学三牌楼校区");
        assertThat(s.getLocationConstraint().getRadiusKm()).isEqualTo(5.0);
    }

    private static AttractionCandidate attractionCandidate(long id) {
        AttractionCandidate candidate = new AttractionCandidate();
        candidate.setAttractionId(id);
        candidate.setName("景点" + id);
        return candidate;
    }
}
