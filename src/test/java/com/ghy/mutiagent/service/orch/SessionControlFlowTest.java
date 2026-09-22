package com.ghy.mutiagent.service.orch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.agent.PreferenceAgent;
import com.ghy.mutiagent.agent.RequirementAgent;
import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.model.ChatStepResult;
import com.ghy.mutiagent.model.FoodCandidate;
import com.ghy.mutiagent.model.HotelCandidate;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.model.enums.TravelStage;
import com.ghy.mutiagent.repository.mapper.DestinationMapper;
import com.ghy.mutiagent.rule.RulePreferenceParser;
import com.ghy.mutiagent.security.AuthenticatedUser;
import com.ghy.mutiagent.service.CandidateService;
import com.ghy.mutiagent.service.ItineraryService;
import com.ghy.mutiagent.service.TravelOperationService;
import com.ghy.mutiagent.service.TravelSessionService;
import com.ghy.mutiagent.service.UsageService;
import com.ghy.mutiagent.trace.TraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionControlFlowTest {

    @Mock TravelSessionService sessionService;
    @Mock DestinationMapper destinationMapper;
    @Mock RulePreferenceParser rulePreferenceParser;
    @Mock PreferenceAgent preferenceAgent;
    @Mock RequirementAgent requirementAgent;
    @Mock CandidateService candidateService;
    @Mock ItineraryService itineraryService;
    @Mock UsageService usageService;
    @Mock TraceService traceService;
    @Mock TravelOperationService operationService;

    private TravelOrchestrator orchestrator;
    private final AuthenticatedUser actor = new AuthenticatedUser(7L, "tester", "USER");

    @BeforeEach
    void setUp() {
        TaskExecutor direct = Runnable::run;
        orchestrator = new TravelOrchestrator(sessionService, destinationMapper, rulePreferenceParser,
                preferenceAgent, requirementAgent, candidateService, itineraryService, usageService,
                traceService, new ObjectMapper(), direct, operationService);
    }

    @Test
    void 候选阶段说重新开始创建隔离新会话且不调用任何Agent() {
        TravelState old = state(TravelStage.ATTRACTIONS);
        when(sessionService.loadOwned("old-session", 7L)).thenReturn(old);

        ChatStepResult result = orchestrator.chat(actor, "old-session", "重新开始");

        assertThat(result.getSessionId()).isNotEqualTo("old-session");
        assertThat(result.getStage()).isEqualTo(TravelStage.PREFERENCE);
        assertThat(result.getQuestion().getField()).isEqualTo("days");
        ArgumentCaptor<TravelState> saved = ArgumentCaptor.forClass(TravelState.class);
        verify(sessionService).save(saved.capture());
        assertThat(saved.getValue().getParentSessionId()).isEqualTo("old-session");
        verify(preferenceAgent, never()).parse(any(), any(), any(), any());
        verify(requirementAgent, never()).analyze(any());
        verify(candidateService, never()).generateAttractions(any());
        verify(candidateService, never()).generateFoods(any());
        verify(candidateService, never()).generateHotels(any());
    }

    @Test
    void 返回景点阶段只保留偏好并清除该阶段及下游结果() {
        TravelState s = state(TravelStage.DONE);
        s.setSelectedAttractionIds(new ArrayList<>(List.of(1L)));
        s.setSelectedFoodIds(new ArrayList<>(List.of(2L)));
        s.setSelectedHotelIds(new ArrayList<>(List.of(3L)));
        s.setFoodPool(List.of(new FoodCandidate()));
        s.setHotelPool(List.of(new HotelCandidate()));
        s.setPlan(new ItineraryPlan());
        s.setItineraryId(99L);
        s.setItineraryText("旧行程");
        when(sessionService.loadOwned("old-session", 7L)).thenReturn(s);

        ChatStepResult result = orchestrator.rewindSession(actor, "old-session", "ATTRACTIONS", 0L);

        assertThat(result.getStage()).isEqualTo(TravelStage.ATTRACTIONS);
        assertThat(s.getSelectedAttractionIds()).isEmpty();
        assertThat(s.getSelectedFoodIds()).isEmpty();
        assertThat(s.getSelectedHotelIds()).isEmpty();
        assertThat(s.getFoodPool()).isNull();
        assertThat(s.getHotelPool()).isNull();
        assertThat(result.getPlan()).isNull();
        assertThat(result.getItineraryId()).isNull();
        assertThat(s.getPreference().getDestinationId()).isEqualTo(1L);
        verify(sessionService).save(s);
        verify(candidateService, never()).generateAttractions(any());
        verify(candidateService, never()).generateFoods(any());
        verify(candidateService, never()).generateHotels(any());
    }

    @Test
    void 当前美食阶段说重新选择只清除美食和酒店下游并保留景点() {
        TravelState s = state(TravelStage.FOODS);
        s.setSelectedAttractionIds(new ArrayList<>(List.of(1L)));
        s.setSelectedFoodIds(new ArrayList<>(List.of(2L)));
        s.setSelectedHotelIds(new ArrayList<>(List.of(3L)));
        s.setHotelPool(List.of(new HotelCandidate()));
        when(sessionService.loadOwned("old-session", 7L)).thenReturn(s);

        ChatStepResult result = orchestrator.chat(actor, "old-session", "重新选择");

        assertThat(result.getStage()).isEqualTo(TravelStage.FOODS);
        assertThat(s.getSelectedAttractionIds()).containsExactly(1L);
        assertThat(s.getSelectedFoodIds()).isEmpty();
        assertThat(s.getSelectedHotelIds()).isEmpty();
        assertThat(s.getHotelPool()).isNull();
        verify(preferenceAgent, never()).parse(any(), any(), any(), any());
        verify(requirementAgent, never()).analyze(any());
        verify(candidateService, never()).generateFoods(any());
        verify(candidateService, never()).generateHotels(any());
    }

    @Test
    void 景点阶段说上一步仍停留景点重选而不是跳到未来阶段() {
        TravelState s = state(TravelStage.ATTRACTIONS);
        s.setSelectedAttractionIds(new ArrayList<>(List.of(1L)));
        when(sessionService.loadOwned("old-session", 7L)).thenReturn(s);

        ChatStepResult result = orchestrator.chat(actor, "old-session", "上一步");

        assertThat(result.getStage()).isEqualTo(TravelStage.ATTRACTIONS);
        assertThat(s.getSelectedAttractionIds()).isEmpty();
    }

    @Test
    void 旧页面版本不能覆盖更新后的会话() {
        TravelState s = state(TravelStage.DONE);
        s.setSessionRevision(3L);
        when(sessionService.loadOwned("old-session", 7L)).thenReturn(s);

        assertThatThrownBy(() -> orchestrator.rewindSession(actor, "old-session", "ATTRACTIONS", 2L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ResultCode.STATE_CONFLICT.getCode());
        verify(sessionService, never()).save(any());
    }

    @Test
    void 异步生成占用会话时拒绝原会话回退() {
        TravelState s = state(TravelStage.DONE);
        when(sessionService.loadOwned("old-session", 7L)).thenReturn(s);
        when(sessionService.hasActiveOperation("old-session")).thenReturn(true);

        assertThatThrownBy(() -> orchestrator.rewindSession(actor, "old-session", "ATTRACTIONS", 0L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ResultCode.STATE_CONFLICT.getCode());
        verify(sessionService, never()).save(any());
    }

    @Test
    void 预算问题输入纯数字直接推进且不写入特殊需求() {
        TravelOrchestrator contextAware = new TravelOrchestrator(sessionService, destinationMapper,
                new RulePreferenceParser(), preferenceAgent, requirementAgent, candidateService,
                itineraryService, usageService, traceService, new ObjectMapper(), Runnable::run,
                operationService);
        TravelState s = state(TravelStage.PREFERENCE);
        s.getPreference().setDays(1);
        s.getPreference().markConfirmed("days");
        s.setCurrentField("totalBudget");
        when(sessionService.loadOwned("old-session", 7L)).thenReturn(s);

        ChatStepResult result = contextAware.chat(actor, "old-session", "600");

        assertThat(s.getPreference().getTotalBudget()).isEqualByComparingTo("600");
        assertThat(s.getPreference().getSpecialRequests()).isNull();
        assertThat(result.getQuestion()).isNotNull();
        assertThat(result.getQuestion().getField()).isEqualTo("peopleCount");
        assertThat(result.getMessage()).contains("预算 600 元");
        verify(preferenceAgent, never()).parse(any(), any(), any(), any());
    }

    private TravelState state(TravelStage stage) {
        TravelState s = new TravelState();
        s.setSessionId("old-session");
        s.setUserId(7L);
        s.setUsername("tester");
        s.setDestinationId(1L);
        s.setDestinationName("南京");
        s.setStage(stage);
        s.setSessionRevision(0L);
        s.getPreference().setDestinationId(1L);
        return s;
    }
}
