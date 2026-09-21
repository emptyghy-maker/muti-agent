package com.ghy.mutiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.agent.ItineraryAgent;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.repository.entity.Attraction;
import com.ghy.mutiagent.repository.entity.Restaurant;
import com.ghy.mutiagent.repository.mapper.AttractionMapper;
import com.ghy.mutiagent.repository.mapper.DestinationMapper;
import com.ghy.mutiagent.repository.mapper.HotelMapper;
import com.ghy.mutiagent.repository.mapper.ItineraryFeedbackMapper;
import com.ghy.mutiagent.repository.mapper.ItineraryMapper;
import com.ghy.mutiagent.repository.mapper.RestaurantMapper;
import com.ghy.mutiagent.trace.TraceContext;
import com.ghy.mutiagent.trace.TraceService;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 夜景时段时长封顶回归（事故复盘）：苏州情侣一日游，回家截止 22:00，
 * 金鸡湖景区（夜景标签，建议时长 3h）夜游按全天 3 小时重算 → 返程 23:00 越界 422。
 * 修复后夜景时段（18:30 后）游览按 2 小时封顶：晚餐 17:30-19:00 → 夜景 19:30-21:30 → 返程 22:00，正好收进截止线。
 */
@ExtendWith(MockitoExtension.class)
class ItineraryNightTimingTest {

    @Mock
    private AttractionMapper attractionMapper;
    @Mock
    private RestaurantMapper restaurantMapper;
    @Mock
    private HotelMapper hotelMapper;
    @Mock
    private ItineraryMapper itineraryMapper;
    @Mock
    private ItineraryFeedbackMapper itineraryFeedbackMapper;
    @Mock
    private DestinationMapper destinationMapper;
    @Mock
    private ItineraryAgent itineraryAgent;
    @Mock
    private TraceService traceService;
    @Mock
    private UsageService usageService;
    @Mock
    private ItineraryCommitService commitService;

    private ItineraryService svc;

    @BeforeEach
    void setUp() {
        svc = new ItineraryService(attractionMapper, restaurantMapper, hotelMapper, itineraryMapper,
                itineraryFeedbackMapper, destinationMapper, itineraryAgent, traceService,
                usageService, new ObjectMapper(), commitService);
        when(traceService.newTrace(anyString(), anyString()))
                .thenAnswer(inv -> new TraceContext(inv.getArgument(0, String.class),
                        inv.getArgument(1, String.class)));
        // 本用例无休息点候选
        when(attractionMapper.selectList(any())).thenReturn(List.of());
    }

    private static Attraction attraction(long id, String name, double hours, String tags,
                                         double lng, double lat) {
        Attraction a = new Attraction();
        a.setId(id);
        a.setName(name);
        a.setCategory("景点");
        a.setSuggestHours(hours);
        a.setIntensity(2);
        a.setTags(tags);
        a.setTicketPrice(BigDecimal.ZERO);
        a.setLng(lng);
        a.setLat(lat);
        return a;
    }

    private static Restaurant restaurant(long id, String name, int avgPrice, double lng, double lat) {
        Restaurant r = new Restaurant();
        r.setId(id);
        r.setName(name);
        r.setCuisine("本地菜");
        r.setAvgPrice(BigDecimal.valueOf(avgPrice));
        r.setLng(lng);
        r.setLat(lat);
        return r;
    }

    @Test
    void 夜景两小时封顶后二十二点截止一日游正常发布() {
        List<Attraction> attractions = List.of(
                attraction(24, "平江路历史街区", 3.0, "街区,citywalk,小吃,情侣,氛围", 120.63, 31.31),
                attraction(43, "金鸡湖月光码头", 1.5, "自然风光,湖景,夜景,音乐喷泉,情侣", 120.72, 31.32),
                attraction(26, "金鸡湖景区", 3.0, "自然风光,湖景,夜景,摩天轮,音乐喷泉,情侣,氛围", 120.72, 31.33));
        when(attractionMapper.selectBatchIds(anyCollection())).thenReturn(attractions);
        when(restaurantMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                restaurant(24, "新梅华餐厅(金鸡湖店)", 120, 120.70, 31.31),
                restaurant(54, "小厨娘淮扬菜", 90, 120.69, 31.32)));
        // 事故复刻：模型原样输出（返程 21:30 合理），重算曾因夜景按 3h 计而推到 23:00
        String planJson = "{\"days\":[{\"dayIndex\":1,\"theme\":\"金鸡湖畔情侣漫游\",\"nodes\":["
                + "{\"type\":\"transport\",\"placeId\":null,\"time\":\"09:00\",\"note\":\"抵达苏州\"},"
                + "{\"type\":\"attraction\",\"placeId\":24,\"time\":\"10:00\",\"note\":\"平江路漫步小吃\"},"
                + "{\"type\":\"restaurant\",\"placeId\":24,\"time\":\"12:00\",\"note\":\"午餐\"},"
                + "{\"type\":\"attraction\",\"placeId\":43,\"time\":\"14:00\",\"note\":\"月光码头看湖景\"},"
                + "{\"type\":\"restaurant\",\"placeId\":54,\"time\":\"17:30\",\"note\":\"晚餐\"},"
                + "{\"type\":\"attraction\",\"placeId\":26,\"time\":\"19:00\",\"note\":\"金鸡湖夜景\"},"
                + "{\"type\":\"transport\",\"placeId\":null,\"time\":\"21:30\",\"note\":\"返程\"}]}]}";
        when(itineraryAgent.plan(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Result.<String>builder().content(planJson).build());
        when(commitService.commitNew(any())).thenReturn(new ItineraryCommitService.CommitResult(1L, 1));

        TravelState st = new TravelState();
        st.setSessionId("s-night-22");
        st.setUserId(1L);
        st.setUsername("admin");
        st.setSelectedAttractionIds(List.of(26L, 43L, 24L));
        st.setSelectedFoodIds(List.of(24L, 54L));
        st.setSelectedHotelIds(List.of());
        st.setNoHotelNeeded(true);
        TravelPreference p = new TravelPreference();
        p.setDays(1);
        p.setPeopleCount(2);
        p.setTotalBudget(BigDecimal.valueOf(600));
        p.setEnergyLevel("一般");
        p.setWakeTime("09:00");
        p.setReturnDeadline("22:00");
        p.setNightPlan("ONE");
        st.setPreference(p);

        svc.generate(st);

        assertThat(st.getPlan()).as("22:00 截止下应正常发布，不再 DAY_END 422").isNotNull();
        List<PlanNode> nodes = st.getPlan().getDays().get(0).getNodes();
        PlanNode night = nodes.stream().filter(n -> n.getPlaceId() != null && n.getPlaceId() == 26L)
                .findFirst().orElseThrow();
        assertThat(night.getDurationMinutes()).as("夜景时段游览按 2 小时封顶").isEqualTo(120);
        PlanNode last = nodes.get(nodes.size() - 1);
        assertThat(last.getType()).isEqualTo("transport");
        assertThat(last.getTime()).as("返程不得晚于回家截止 22:00").isLessThanOrEqualTo("22:00");
        assertThat(Boolean.TRUE.equals(st.getPendingBudgetConfirm())).isFalse();
    }
}
