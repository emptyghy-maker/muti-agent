package com.ghy.mutiagent.service.orch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.model.AttractionCandidate;
import com.ghy.mutiagent.model.ChatStepResult;
import com.ghy.mutiagent.model.HotelCandidate;
import com.ghy.mutiagent.model.PlanQuizRequest;
import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.model.enums.TravelStage;
import com.ghy.mutiagent.repository.mapper.DestinationMapper;
import com.ghy.mutiagent.security.AuthenticatedUser;
import com.ghy.mutiagent.agent.PreferenceAgent;
import com.ghy.mutiagent.agent.RequirementAgent;
import com.ghy.mutiagent.rule.RulePreferenceParser;
import com.ghy.mutiagent.service.CandidateService;
import com.ghy.mutiagent.service.GenerationRegistry;
import com.ghy.mutiagent.service.ItineraryService;
import com.ghy.mutiagent.service.TravelOperationService;
import com.ghy.mutiagent.service.TravelSessionService;
import com.ghy.mutiagent.service.UsageService;
import com.ghy.mutiagent.trace.TraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 行程偏好问卷（PLAN_QUIZ）闸门：酒店确认/跳过路径在生成前先过问卷；
 * 问卷提交后复用生成链路；跳过景点环节时不再问活动倾向与夜景数量。
 */
@ExtendWith(MockitoExtension.class)
class TravelOrchestratorPlanQuizTest {

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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TaskExecutor taskExecutor = Runnable::run;
    private final GenerationRegistry registry = new GenerationRegistry();

    private TravelOrchestrator orchestrator;
    private AuthenticatedUser actor;

    @BeforeEach
    void setUp() {
        orchestrator = new TravelOrchestrator(sessionService, destinationMapper, rulePreferenceParser,
                preferenceAgent, requirementAgent, candidateService, itineraryService, usageService,
                traceService, objectMapper, taskExecutor, operationService);
        orchestrator.generationRegistry = registry;
        actor = new AuthenticatedUser(1L, "admin", "ADMIN");
    }

    private TravelState hotelState() {
        TravelState s = new TravelState();
        s.setSessionId("s1");
        s.setUserId(1L);
        s.setUsername("admin");
        s.setStage(TravelStage.HOTELS);
        HotelCandidate h = new HotelCandidate();
        h.setHotelId(11L);
        s.setHotelPool(List.of(h));
        s.setPreference(new TravelPreference());
        return s;
    }

    private TravelState quizState() {
        TravelState s = new TravelState();
        s.setSessionId("s1");
        s.setUserId(1L);
        s.setUsername("admin");
        s.setStage(TravelStage.PLAN_QUIZ);
        s.setSelectedHotelIds(new java.util.ArrayList<>(List.of(11L)));
        s.setSelectedAttractionIds(new java.util.ArrayList<>(List.of(43L)));
        AttractionCandidate a = new AttractionCandidate();
        a.setAttractionId(43L);
        a.setName("金鸡湖月光码头");
        a.setTags("夜景,湖景,情侣");
        s.setAttractionPool(List.of(a));
        s.setPreference(new TravelPreference());
        return s;
    }

    @Test
    void hotelConfirmWithoutQuizReturnsQuizStage() {
        TravelState s = hotelState();
        when(sessionService.loadOwned("s1", 1L)).thenReturn(s);

        ChatStepResult r = orchestrator.confirmCandidates(actor, "s1", "HOTEL", List.of(11L), false);

        assertThat(r.getStage()).isEqualTo(TravelStage.PLAN_QUIZ);
        assertThat(r.getPlanQuiz()).isNotNull();
        assertThat(r.getPlanQuiz().isHotelSelected()).isTrue();
        verify(itineraryService, never()).generate(s);
        verify(sessionService, times(1)).save(s);
    }

    @Test
    void submitQuizGeneratesOnceAndReachesDone() {
        TravelState s = quizState();
        when(sessionService.loadOwned("s1", 1L)).thenReturn(s);
        PlanQuizRequest req = new PlanQuizRequest();
        req.setSessionId("s1");
        req.setWakeTime("09:00");
        req.setReturnDeadline("22:00");
        req.setActivityBias("BALANCED");
        req.setNightPlan("ONE");

        ChatStepResult r = orchestrator.submitPlanQuiz(actor, req);

        verify(itineraryService, times(1)).generate(s);
        assertThat(r.getStage()).isEqualTo(TravelStage.DONE);
        assertThat(s.getPreference().getWakeTime()).isEqualTo("09:00");
        assertThat(s.getPreference().getReturnDeadline()).isEqualTo("22:00");
        assertThat(s.getPreference().getActivityBias()).isEqualTo("BALANCED");
        assertThat(s.getPreference().getNightPlan()).isEqualTo("ONE");
        assertThat(s.getPlanQuizAnswered()).isTrue();
    }

    @Test
    void submitQuizPersistsAnswersBeforeGenerationFails() {
        // 事故复盘：生成 422 时问卷答案随保存跳过而丢失，用户回到问卷重答；
        // 答案必须在调用生成前落库（stage 推进 ITINERARY），失败后可用自由文本直接重排
        TravelState s = quizState();
        when(sessionService.loadOwned("s1", 1L)).thenReturn(s);
        org.mockito.Mockito.doThrow(new BizException(5002, "行程生成未通过发布检查"))
                .when(itineraryService).generate(s);
        PlanQuizRequest req = new PlanQuizRequest();
        req.setSessionId("s1");
        req.setWakeTime("09:00");
        req.setReturnDeadline("22:00");
        req.setActivityBias("BALANCED");
        req.setNightPlan("ONE");

        assertThatThrownBy(() -> orchestrator.submitPlanQuiz(actor, req))
                .isInstanceOf(BizException.class);

        assertThat(s.getPlanQuizAnswered()).isTrue();
        assertThat(s.getPreference().getWakeTime()).isEqualTo("09:00");
        assertThat(s.getStage()).isEqualTo(TravelStage.ITINERARY);
        verify(sessionService, times(1)).save(s);
    }

    @Test
    void submitQuizRejectsInvalidWakeTime() {
        TravelState s = quizState();
        when(sessionService.loadOwned("s1", 1L)).thenReturn(s);
        PlanQuizRequest req = new PlanQuizRequest();
        req.setSessionId("s1");
        req.setWakeTime("03:00");
        req.setReturnDeadline("22:00");

        assertThatThrownBy(() -> orchestrator.submitPlanQuiz(actor, req))
                .isInstanceOf(BizException.class);
        verify(itineraryService, never()).generate(any());
    }

    @Test
    void attractionsSkippedQuizOmitsBiasAndNightQuestions() {
        TravelState s = hotelState();
        s.setNoAttractionNeeded(true);
        when(sessionService.loadOwned("s1", 1L)).thenReturn(s);

        ChatStepResult r = orchestrator.confirmCandidates(actor, "s1", "HOTEL", List.of(11L), false);

        assertThat(r.getStage()).isEqualTo(TravelStage.PLAN_QUIZ);
        assertThat(r.getPlanQuiz().isAskActivityBias()).isFalse();
        assertThat(r.getPlanQuiz().isAskNightPlan()).isFalse();
    }
}
