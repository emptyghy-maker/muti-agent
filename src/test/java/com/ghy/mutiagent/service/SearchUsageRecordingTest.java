package com.ghy.mutiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.config.CandidateFoodConfig;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.model.WebFoodCandidate;
import com.ghy.mutiagent.repository.mapper.AttractionMapper;
import com.ghy.mutiagent.repository.mapper.HotelMapper;
import com.ghy.mutiagent.repository.mapper.RestaurantMapper;
import com.ghy.mutiagent.agent.AttractionAgent;
import com.ghy.mutiagent.agent.FoodAgent;
import com.ghy.mutiagent.agent.HotelAgent;
import com.ghy.mutiagent.trace.TraceContext;
import com.ghy.mutiagent.trace.TraceService;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O1-T09 证据（生产接线）：联网搜索返回的 token 必须如实写入 attempt 级用量记录（不再记 0），
 * 与 turn 级汇总、报告口径保持一致的数据源。
 */
class SearchUsageRecordingTest {

    @Test
    void searchTokensRecordedOnAttemptRow() throws Exception {
        RestaurantMapper restaurantMapper = mock(RestaurantMapper.class);
        TraceService traceService = mock(TraceService.class);
        UsageService usageService = mock(UsageService.class);
        DashScopeSearchClient searchClient = mock(DashScopeSearchClient.class);
        ObjectMapper objectMapper = new ObjectMapper();

        CandidateService service = new CandidateService(
                mock(AttractionMapper.class), restaurantMapper, mock(HotelMapper.class),
                mock(AttractionAgent.class), mock(FoodAgent.class), mock(HotelAgent.class),
                traceService, usageService, objectMapper, mock(CandidateFoodConfig.class));
        ReflectionTestUtils.setField(service, "dashScopeSearchClient", searchClient);
        ReflectionTestUtils.setField(service, "searchModel", "qwen3.8-max");

        when(traceService.newTrace(anyString(), anyString()))
                .thenReturn(new TraceContext("s1", "SearchAgent", TimeSource.SYSTEM));
        when(restaurantMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(searchClient.search(anyString(), anyString())).thenReturn(
                new DashScopeSearchClient.SearchResult(
                        "{\"items\":[{\"name\":\"网搜餐厅\",\"cuisine\":\"川菜\",\"avgPrice\":\"50\","
                                + "\"address\":\"某路1号\",\"why\":\"好评\"}]}",
                        100, 50));

        TravelState state = new TravelState();
        state.setSessionId("s1");
        state.setUserId(1L);
        state.setUsername("u1");
        state.setDestinationId(1L);
        state.setDestinationName("杭州");

        List<WebFoodCandidate> result = service.searchFoodsOnline(state);

        assertNotNull(result);
        assertEquals(1, result.size());
        ArgumentCaptor<TokenUsage> usageCaptor = ArgumentCaptor.forClass(TokenUsage.class);
        verify(usageService).recordAgent(eq("s1"), eq(1L), eq("u1"), eq("FOODS"),
                eq("美食联网检索"), eq("SearchAgent"), usageCaptor.capture(), anyLong(),
                eq("SUCCESS"), anyString(), eq("qwen3.8-max"), anyString(), anyString());
        TokenUsage recorded = usageCaptor.getValue();
        assertEquals(100, recorded.inputTokenCount(), "搜索 prompt token 必须落 attempt 明细行");
        assertEquals(50, recorded.outputTokenCount(), "搜索 completion token 必须落 attempt 明细行");
    }
}
