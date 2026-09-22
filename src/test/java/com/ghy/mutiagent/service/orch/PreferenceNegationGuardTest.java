package com.ghy.mutiagent.service.orch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.agent.PreferenceAgent;
import com.ghy.mutiagent.agent.RequirementAgent;
import com.ghy.mutiagent.model.ChatStepResult;
import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.model.enums.TravelStage;
import com.ghy.mutiagent.repository.mapper.DestinationMapper;
import com.ghy.mutiagent.rule.RuleParseResult;
import com.ghy.mutiagent.rule.RulePreferenceParser;
import com.ghy.mutiagent.security.AuthenticatedUser;
import com.ghy.mutiagent.service.CandidateService;
import com.ghy.mutiagent.service.GenerationRegistry;
import com.ghy.mutiagent.service.ItineraryService;
import com.ghy.mutiagent.service.TravelOperationService;
import com.ghy.mutiagent.service.TravelSessionService;
import com.ghy.mutiagent.service.UsageService;
import com.ghy.mutiagent.trace.TraceContext;
import com.ghy.mutiagent.trace.TraceService;
import dev.langchain4j.model.output.TokenUsage;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 偏好问询的预期外输入治理：
 * - 裸否定词快路（零 LLM）：问酒店答「不要」=不需要酒店、问景点=不需要景点、问其他字段=按默认；
 * - LLM 消解带当前问题原文上下文，skip 字段承载 noHotel/noAttraction 语义（与问题不符时忽略）；
 * - 兜底写入的特殊需求不算正式回答：最终问题照常问，题干回显已记下内容；
 * - 重问护栏：同一字段连续两轮没推进，第三轮确定性带过，不把用户困在同一问题上。
 */
@ExtendWith(MockitoExtension.class)
class PreferenceNegationGuardTest {

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

    @BeforeEach
    void setUp() {
        orchestrator = new TravelOrchestrator(sessionService, destinationMapper, rulePreferenceParser,
                preferenceAgent, requirementAgent, candidateService, itineraryService, usageService,
                traceService, objectMapper, taskExecutor, operationService);
        orchestrator.generationRegistry = new GenerationRegistry();
        // 规则解析默认零命中：原文作为残余进入快照（由裸否定快路/LLM 消解决定去向）
        lenient().when(rulePreferenceParser.parseResult(anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    RuleParseResult r = new RuleParseResult();
                    r.setUnresolvedText(inv.getArgument(0));
                    return r;
                });
        lenient().when(traceService.newTrace(anyString(), anyString()))
                .thenAnswer(inv -> new TraceContext(inv.getArgument(0, String.class),
                        inv.getArgument(1, String.class)));
    }

    /** 问询推进到指定字段的偏好状态：排在 currentField 之前的字段已答，之后的全未答 */
    private TravelState prefState(String currentField) {
        TravelState s = new TravelState();
        s.setSessionId("s-neg");
        s.setUserId(1L);
        s.setUsername("admin");
        s.setStage(TravelStage.PREFERENCE);
        s.setDestinationId(1L);
        s.setDestinationName("南京");
        TravelPreference p = new TravelPreference();
        List<String> order = List.of("days", "totalBudget", "peopleCount", "attractionType",
                "foodTaste", "energyLevel", "hotelStyle", "specialRequests");
        int idx = order.indexOf(currentField);
        if (idx < 0) {
            throw new IllegalArgumentException("未知字段：" + currentField);
        }
        for (int i = 0; i < idx; i++) {
            String f = order.get(i);
            switch (f) {
                case "days" -> { p.setDays(3); p.markConfirmed("days"); }
                case "totalBudget" -> { p.setTotalBudget(BigDecimal.valueOf(3000)); p.markConfirmed("totalBudget"); }
                case "peopleCount" -> { p.setPeopleCount(2); p.markConfirmed("peopleCount"); }
                case "attractionType" -> { p.setAttractionType("混合"); p.markConfirmed("attractionType"); }
                case "foodTaste" -> { p.setFoodTaste("本地特色菜"); p.markConfirmed("foodTaste"); }
                case "energyLevel" -> { p.setEnergyLevel("一般"); p.markConfirmed("energyLevel"); }
                case "hotelStyle" -> { p.setHotelStyle("性价比优先"); p.markConfirmed("hotelStyle"); }
                case "specialRequests" -> { p.setSpecialRequests(""); p.markDefaulted("specialRequests"); }
                default -> { }
            }
        }
        s.setPreference(p);
        s.setCurrentField(currentField);
        return s;
    }

    private ChatStepResult answer(TravelState s, String message) {
        return (ChatStepResult) ReflectionTestUtils.invokeMethod(orchestrator, "chatPreference", s, message);
    }

    @Test
    void 酒店环节裸否定词秒回不需要酒店且零LLM() {
        TravelState s = prefState("hotelStyle");

        ChatStepResult r = answer(s, "不要");

        // 语义：不需要酒店（跳过酒店挑选），且不调 LLM
        assertThat(Boolean.TRUE.equals(s.getNoHotelNeeded())).isTrue();
        verify(preferenceAgent, never()).parse(anyString(), anyString(), anyString(), anyString());
        assertThat(r.getMessage()).contains("不需要酒店");
        // 酒店问题不再追问，推进到最后一道特殊需求问题
        assertThat(r.getQuestion()).isNotNull();
        assertThat(r.getQuestion().getField()).isEqualTo("specialRequests");
    }

    @Test
    void 景点环节裸否定词映射为不需要景点() {
        TravelState s = prefState("attractionType");

        ChatStepResult r = answer(s, "不要");

        assertThat(Boolean.TRUE.equals(s.getNoAttractionNeeded())).isTrue();
        verify(preferenceAgent, never()).parse(anyString(), anyString(), anyString(), anyString());
        assertThat(r.getMessage()).contains("不需要安排景点");
        assertThat(r.getQuestion().getField()).isEqualTo("foodTaste");
    }

    @Test
    void 数值字段裸否定词按默认带过() {
        TravelState s = prefState("days");

        ChatStepResult r = answer(s, "不要");

        verify(preferenceAgent, never()).parse(anyString(), anyString(), anyString(), anyString());
        // days 置 UNSURE → 默认值处理，字段不再缺失
        assertThat(s.getPreference().getDays()).isNull();
        assertThat(s.getPreference().isMissing("days")).isFalse();
        assertThat(r.getMessage()).contains("先按推荐处理");
        assertThat(r.getQuestion().getField()).isEqualTo("totalBudget");
    }

    @Test
    void 特殊需求问题答裸否定词视为没有并交接() {
        TravelState s = prefState("specialRequests");
        when(requirementAgent.analyze(anyString())).thenReturn(
                Result.<String>builder().content(
                        "{\"mode\":\"agent\",\"focus\":[\"常规\"],\"brief\":\"常规定制。\","
                                + "\"needs\":{\"path\":3,\"cost\":2,\"sightseeing\":3,\"food\":3},"
                                + "\"tags\":[]}").build());

        ChatStepResult r = answer(s, "不要");

        verify(preferenceAgent, never()).parse(anyString(), anyString(), anyString(), anyString());
        assertThat(s.getPreference().isMissing("specialRequests")).isFalse();
        // 全部字段齐备 → 直接交接进入候选阶段
        assertThat(r.getStage()).isEqualTo(TravelStage.ATTRACTIONS);
    }

    @Test
    void llm带问题上下文输出skip映射为不需要酒店() {
        TravelState s = prefState("hotelStyle");
        when(preferenceAgent.parse(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Result.<String>builder()
                        .content("{\"updates\":{\"hotelStyle\":\"UNSURE\"},\"conflict\":null,\"skip\":\"noHotel\"}")
                        .tokenUsage(new TokenUsage(1, 1))
                        .build());

        answer(s, "不用了谢谢"); // 不是裸否定词：走 LLM 消解

        verify(preferenceAgent).parse(anyString(), eq("hotelStyle"),
                eq("酒店更看重什么？"), eq("不用了谢谢"));
        assertThat(Boolean.TRUE.equals(s.getNoHotelNeeded())).isTrue();
    }

    @Test
    void llmSkip与当前问题不符时忽略() {
        TravelState s = prefState("days");
        when(preferenceAgent.parse(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Result.<String>builder()
                        .content("{\"updates\":{\"days\":\"UNSURE\"},\"conflict\":null,\"skip\":\"noHotel\"}")
                        .tokenUsage(new TokenUsage(1, 1))
                        .build());

        answer(s, "随便写点什么"); // 非裸否定词：走 LLM 消解

        // 当前问题不是酒店：skip 被忽略（防模型幻觉），days 正常按默认处理
        assertThat(s.getNoHotelNeeded()).isNull();
        assertThat(s.getPreference().isMissing("days")).isFalse();
    }

    @Test
    void 兜底特殊需求不吞最终问题且题干回显() {
        TravelState s = prefState("hotelStyle");
        // 规则命中 hotelStyle（推进当前字段），残余 abc 交给 LLM 消解（失败兜底进特殊需求）
        when(rulePreferenceParser.parseResult(anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    RuleParseResult r = new RuleParseResult();
                    r.getUpdates().put("hotelStyle", "性价比优先");
                    r.setUnresolvedText("abc");
                    return r;
                });
        when(preferenceAgent.parse(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("HttpTimeoutException: request timed out"));

        ChatStepResult r = answer(s, "性价比高的 abc");

        // 兜底记下原文（不丢信息），但不算正式回答：最终问题照常问并回显已记下内容
        assertThat(s.getPreference().getSpecialRequests()).isEqualTo("abc");
        assertThat(s.getPreference().isMissing("specialRequests")).isTrue();
        assertThat(s.getPreference().isMissing("hotelStyle")).isFalse();
        assertThat(r.getQuestion().getField()).isEqualTo("specialRequests");
        assertThat(r.getQuestion().getText()).contains("目前已记下这些特殊要求：abc");
        assertThat(r.getQuestion().getText()).contains("特别想去的地方");
    }

    @Test
    void 重问护栏第三轮确定性带过() {
        TravelState s = prefState("days");
        when(preferenceAgent.parse(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Result.<String>builder()
                        .content("{\"updates\":{},\"conflict\":null,\"skip\":null}")
                        .tokenUsage(new TokenUsage(1, 1))
                        .build());

        ChatStepResult r1 = answer(s, "呵呵");
        ChatStepResult r2 = answer(s, "再想想");
        // 前两轮：没推进，还是问天数（不无限循环的前提是第三轮带过）
        assertThat(r1.getQuestion().getField()).isEqualTo("days");
        assertThat(r2.getQuestion().getField()).isEqualTo("days");
        assertThat(s.getPreference().isMissing("days")).isTrue();

        ChatStepResult r3 = answer(s, "还没想好");

        // 第三轮：确定性带过（默认值），推进到预算问题
        assertThat(s.getPreference().isMissing("days")).isFalse();
        assertThat(s.getPreference().getDays()).isNull();
        assertThat(r3.getMessage()).contains("先按推荐处理");
        assertThat(r3.getQuestion().getField()).isEqualTo("totalBudget");
    }
}
