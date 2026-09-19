package com.ghy.mutiagent.service.orch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.agent.PreferenceAgent;
import com.ghy.mutiagent.agent.RequirementAgent;
import com.ghy.mutiagent.model.ChatStepResult;
import com.ghy.mutiagent.model.HotelCandidate;
import com.ghy.mutiagent.model.ResumeView;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.model.enums.TravelStage;
import com.ghy.mutiagent.repository.mapper.DestinationMapper;
import com.ghy.mutiagent.rule.RulePreferenceParser;
import com.ghy.mutiagent.security.AuthenticatedUser;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 酒店确认同步生成的「生成中守卫」契约测试（进程内注册表，不落库、不改快照）：
 * - 生成进行中重复确认：返回「生成中」提示，绝不二次调用生成、不落库；
 * - 正常路径：生成一次 + 成功落库一次，结束后释放标记；
 * - 生成失败：异常向上传播，不落库（S06-A 快照不变），标记释放；
 * - 恢复视图：generating 由注册表回填。
 */
@ExtendWith(MockitoExtension.class)
class TravelOrchestratorGenerateGuardTest {

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

    private TravelOrchestrator orchestrator;
    private final GenerationRegistry registry = new GenerationRegistry();
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
        return s;
    }

    @Test
    void guardReturnsGeneratingMessageWithoutRegenerate() {
        TravelState s = hotelState();
        when(sessionService.loadOwned("s1", 1L)).thenReturn(s);
        registry.begin("s1", 60_000L); // 上一轮生成仍在进行

        ChatStepResult r = orchestrator.confirmCandidates(actor, "s1", "HOTEL", List.of(11L), false);

        assertThat(r.getMessage()).contains("生成中");
        assertThat(r.getStage()).isEqualTo(TravelStage.HOTELS);
        verify(itineraryService, never()).generate(s);
        verify(sessionService, never()).save(s);
    }

    @Test
    void normalPathGeneratesOnceAndReleasesMark() {
        TravelState s = hotelState();
        when(sessionService.loadOwned("s1", 1L)).thenReturn(s);

        ChatStepResult r = orchestrator.confirmCandidates(actor, "s1", "HOTEL", List.of(11L), false);

        assertThat(r.getStage()).isEqualTo(TravelStage.DONE);
        verify(itineraryService, times(1)).generate(s);
        // 只有成功后的结果落库一次（提交失败快照不变语义保持）
        verify(sessionService, times(1)).save(s);
        assertThat(registry.isGenerating("s1")).isFalse();
    }

    @Test
    void failedGeneratePropagatesWithoutSaveAndReleasesMark() {
        TravelState s = hotelState();
        when(sessionService.loadOwned("s1", 1L)).thenReturn(s);
        org.mockito.Mockito.doThrow(new RuntimeException("model down")).when(itineraryService).generate(s);

        assertThatThrownBy(() -> orchestrator.confirmCandidates(actor, "s1", "HOTEL", List.of(11L), false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("model down");

        verify(sessionService, never()).save(s);
        assertThat(registry.isGenerating("s1")).isFalse();
    }

    @Test
    void resumableViewsCarryGeneratingFromRegistry() {
        ResumeView v = new ResumeView();
        v.setSessionId("s1");
        v.setStage(TravelStage.HOTELS);
        when(sessionService.findResumable(1L)).thenReturn(Optional.of(v));
        registry.begin("s1", 60_000L);

        assertThat(orchestrator.resumableSession(actor).isGenerating()).isTrue();

        registry.end("s1");
        assertThat(orchestrator.resumableSession(actor).isGenerating()).isFalse();
    }

    @Test
    void resumableByIdViewsCarryGeneratingFromRegistry() {
        ResumeView v = new ResumeView();
        v.setSessionId("s1");
        v.setStage(TravelStage.HOTELS);
        when(sessionService.findResumable(1L, "s1")).thenReturn(Optional.of(v));
        registry.begin("s1", 60_000L);

        assertThat(orchestrator.resumableSession(actor, "s1").isGenerating()).isTrue();
    }
}
