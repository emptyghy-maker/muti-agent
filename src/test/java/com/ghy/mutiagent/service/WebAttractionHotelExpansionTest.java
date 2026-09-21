package com.ghy.mutiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.agent.AttractionAgent;
import com.ghy.mutiagent.agent.FoodAgent;
import com.ghy.mutiagent.agent.HotelAgent;
import com.ghy.mutiagent.config.CandidateFoodConfig;
import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.model.enums.TravelStage;
import com.ghy.mutiagent.repository.entity.Attraction;
import com.ghy.mutiagent.repository.entity.Hotel;
import com.ghy.mutiagent.repository.entity.WebFoodAudit;
import com.ghy.mutiagent.repository.mapper.AttractionMapper;
import com.ghy.mutiagent.repository.mapper.HotelMapper;
import com.ghy.mutiagent.repository.mapper.RestaurantMapper;
import com.ghy.mutiagent.repository.mapper.WebFoodAuditMapper;
import com.ghy.mutiagent.trace.TraceContext;
import com.ghy.mutiagent.trace.TraceService;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 景点/酒店联网扩充链路（三通道对称）：AI 精挑覆盖不足 → 自动联网检索 → 确定性校验（恶意条目拒绝）→
 * 入库（参数化 insert）+ 审计（place_type 区分类型）→ 通过校验的网搜行并入候选池优先展示，
 * 无坐标网搜酒店距离为 null 不参与距离排序（不 NPE）。
 */
class WebAttractionHotelExpansionTest {

    private AttractionMapper attractionMapper;
    private HotelMapper hotelMapper;
    private AttractionAgent attractionAgent;
    private HotelAgent hotelAgent;
    private TraceService traceService;
    private UsageService usageService;
    private DashScopeSearchClient searchClient;
    private WebFoodAuditMapper auditMapper;
    private CandidateService svc;
    private List<Attraction> webAttractions;
    private List<Hotel> webHotels;

    @BeforeEach
    void setUp() {
        attractionMapper = mock(AttractionMapper.class);
        hotelMapper = mock(HotelMapper.class);
        attractionAgent = mock(AttractionAgent.class);
        hotelAgent = mock(HotelAgent.class);
        traceService = mock(TraceService.class);
        usageService = mock(UsageService.class);
        searchClient = mock(DashScopeSearchClient.class);
        auditMapper = mock(WebFoodAuditMapper.class);
        TraceContext ctx = mock(TraceContext.class);
        when(traceService.newTrace(anyString(), anyString())).thenReturn(ctx);
        svc = new CandidateService(
                attractionMapper, mock(RestaurantMapper.class), hotelMapper,
                attractionAgent, mock(FoodAgent.class), hotelAgent,
                traceService, usageService, new ObjectMapper(), new CandidateFoodConfig());
        ReflectionTestUtils.setField(svc, "dashScopeSearchClient", searchClient);
        ReflectionTestUtils.setField(svc, "searchModel", "qwen3.8-max");
        ReflectionTestUtils.setField(svc, "webFoodAuditMapper", auditMapper);

        // insert 模拟真实入库：回填 id 并记录到列表（供 webExpanded* 再查询）
        webAttractions = new ArrayList<>();
        webHotels = new ArrayList<>();
        AtomicInteger idSeq = new AtomicInteger(100);
        when(attractionMapper.insert(any(Attraction.class))).thenAnswer(inv -> {
            Attraction a = inv.getArgument(0);
            a.setId((long) idSeq.incrementAndGet());
            webAttractions.add(a);
            return 1;
        });
        when(hotelMapper.insert(any(Hotel.class))).thenAnswer(inv -> {
            Hotel h = inv.getArgument(0);
            h.setId((long) idSeq.incrementAndGet());
            webHotels.add(h);
            return 1;
        });
        when(attractionMapper.selectCount(any())).thenReturn(0L);
        when(hotelMapper.selectCount(any())).thenReturn(0L);
        // 所有 selectList 返回 KB 池 + 当前已入库的网搜行（生产侧 webExpanded* 按 source 过滤区分）
        when(attractionMapper.selectList(any()))
                .thenAnswer(inv -> {
                    List<Attraction> all = new ArrayList<>(kbAttractions());
                    all.addAll(webAttractions);
                    return all;
                });
        when(hotelMapper.selectList(any()))
                .thenAnswer(inv -> {
                    List<Hotel> all = new ArrayList<>(kbHotels());
                    all.addAll(webHotels);
                    return all;
                });
    }

    private static List<Attraction> kbAttractions() {
        List<Attraction> list = new ArrayList<>();
        long id = 1;
        for (String[] row : new String[][]{
                {"西湖", "自然风光", "湖景/免费/户外", "0", "4.9"},
                {"灵隐寺", "文化历史", "历史/文化/寺庙", "45", "4.8"},
                {"宋城", "打卡拍照", "演艺/街区/亲子", "80", "4.6"},
                {"雷峰塔", "文化历史", "历史/登高/湖景", "40", "4.7"},
                {"植物园", "自然风光", "自然/亲子/安静", "10", "4.4"},
                {"博物馆", "文化历史", "文化/室内/免费", "0", "4.5"},
                {"动物园", "娱乐项目", "亲子/户外", "60", "4.3"},
                {"湿地公园", "自然风光", "自然/骑行/免费", "0", "4.4"},
                {"老街", "打卡拍照", "街区/小吃/氛围", "0", "4.6"},
                {"摩天轮", "打卡拍照", "夜景/情侣/登高", "70", "4.5"}}) {
            Attraction a = new Attraction();
            a.setId(id++);
            a.setName(row[0]);
            a.setCategory(row[1]);
            a.setFeatures(row[2]);
            a.setTicketPrice(new BigDecimal(row[3]));
            a.setRating(Double.parseDouble(row[4]));
            a.setLng(120.1 + id * 0.01);
            a.setLat(30.2 + id * 0.01);
            a.setStatus(1);
            list.add(a);
        }
        return list;
    }

    private static List<Hotel> kbHotels() {
        List<Hotel> list = new ArrayList<>();
        long id = 1;
        for (String[] row : new String[][]{
                {"湖景大酒店", "520", "4.7", "高档", "湖景/情侣"},
                {"老牌饭店", "380", "4.5", "舒适", "老牌/市中心"},
                {"快捷酒店", "220", "4.2", "经济", "性价比/近地铁"},
                {"度假村", "680", "4.6", "高档", "亲子/安静"}}) {
            Hotel h = new Hotel();
            h.setId(id++);
            h.setName(row[0]);
            h.setPricePerNight(new BigDecimal(row[1]));
            h.setRating(Double.parseDouble(row[2]));
            h.setLevel(row[3]);
            h.setFeatures(row[4]);
            h.setLng(120.1 + id * 0.01);
            h.setLat(30.2 + id * 0.01);
            h.setStatus(1);
            list.add(h);
        }
        return list;
    }

    private TravelState state() {
        TravelState st = new TravelState();
        st.setSessionId("m-web-poi");
        st.setUserId(1L);
        st.setDestinationId(1L);
        st.setDestinationName("杭州");
        st.setStage(TravelStage.ATTRACTIONS);
        st.setExtraRequest("杭州西湖边，想要夜景和轻松氛围");
        TravelPreference p = new TravelPreference();
        p.setDays(2);
        st.setPreference(p);
        return st;
    }

    @Test
    void insufficientAttractionsTriggerSearchAndMergeValidatedOnes() throws Exception {
        // AI 精挑：仅 3 个（低于下限 8）→ 确定性触发联网检索
        when(attractionAgent.select(anyString(), anyString(), anyInt())).thenReturn(
                Result.<String>builder()
                        .content("{\"items\":[{\"attractionId\":1},{\"attractionId\":5},{\"attractionId\":9}],"
                                + "\"advice\":\"符合夜景且轻松的景点较少，建议扩大范围\"}")
                        .tokenUsage(new TokenUsage(1, 1))
                        .build());
        // 联网检索：2 条合法 + 1 条恶意（<script>） + 1 条缺标签
        when(searchClient.search(anyString(), anyString())).thenReturn(
                new DashScopeSearchClient.SearchResult(
                        "{\"items\":["
                                + "{\"name\":\"宝石山\",\"category\":\"自然风光\",\"tags\":\"夜景,户外,免费\","
                                + "\"ticketPrice\":\"0\",\"address\":\"西湖区宝石山\",\"why\":\"山顶看西湖夜景\"},"
                                + "{\"name\":\"运河夜游码头\",\"category\":\"打卡拍照\",\"tags\":\"夜景,游船,氛围\","
                                + "\"ticketPrice\":\"120\",\"address\":\"拱墅区运河\",\"why\":\"乘船夜游氛围好\"},"
                                + "{\"name\":\"<script>alert(1)</script>\",\"category\":\"打卡拍照\",\"tags\":\"夜景\","
                                + "\"ticketPrice\":\"0\",\"address\":\"x\",\"why\":\"x\"},"
                                + "{\"name\":\"无标签景点\",\"category\":\"打卡拍照\",\"tags\":\"\","
                                + "\"ticketPrice\":\"0\",\"address\":\"西湖区\",\"why\":\"x\"}"
                                + "]}",
                        100, 50));

        TravelState st = state();
        svc.generateAttractions(st);

        // 覆盖不足触发联网检索
        verify(searchClient, times(1)).search(anyString(), anyString());
        // 只接受 2 条合法条目
        assertThat(st.getWebAttractionCandidates()).hasSize(2);
        // 合法条目入库（恶意与缺标签条目不 insert）
        verify(attractionMapper, times(2)).insert(any(Attraction.class));
        // 审计：2 ACCEPT + 2 REJECT，且 place_type=ATTRACTION
        ArgumentCaptor<WebFoodAudit> auditCaptor = ArgumentCaptor.forClass(WebFoodAudit.class);
        verify(auditMapper, times(4)).insert(auditCaptor.capture());
        assertThat(auditCaptor.getAllValues()).filteredOn(a -> "ACCEPT".equals(a.getAction())).hasSize(2);
        assertThat(auditCaptor.getAllValues()).filteredOn(a -> "REJECT".equals(a.getAction())).hasSize(2);
        assertThat(auditCaptor.getAllValues())
                .allSatisfy(a -> assertThat(a.getPlaceType()).isEqualTo("ATTRACTION"));
        // 通过校验的网搜景点并入候选池（可直接勾选），扩充说明追加到 advice
        List<String> poolNames = st.getAttractionPool().stream().map(c -> c.getName()).toList();
        assertThat(poolNames).contains("宝石山", "运河夜游码头");
        assertThat(poolNames).doesNotContain("<script>alert(1)</script>", "无标签景点");
        assertThat(st.getCandidateAdvice()).contains("已联网检索扩充 2 个");
    }

    @Test
    void attractionAiNoMatchTriggersSearchAndBackfillsPool() throws Exception {
        // AI 明确表示池内无匹配（items 为空）：合法语义结果 → 触发检索 + 规则池兜底
        when(attractionAgent.select(anyString(), anyString(), anyInt())).thenReturn(
                Result.<String>builder()
                        .content("{\"items\":[],\"advice\":\"池内没有符合你要求的景点\"}")
                        .tokenUsage(new TokenUsage(1, 1))
                        .build());
        when(searchClient.search(anyString(), anyString())).thenReturn(
                new DashScopeSearchClient.SearchResult(
                        "{\"items\":["
                                + "{\"name\":\"宝石山\",\"category\":\"自然风光\",\"tags\":\"夜景,户外,免费\","
                                + "\"ticketPrice\":\"0\",\"address\":\"西湖区宝石山\",\"why\":\"山顶看西湖夜景\"}"
                                + "]}",
                        100, 50));

        TravelState st = state();
        svc.generateAttractions(st);

        verify(searchClient, times(1)).search(anyString(), anyString());
        List<String> poolNames = st.getAttractionPool().stream().map(c -> c.getName()).toList();
        assertThat(poolNames).contains("宝石山");
        // 网搜不足下限时规则池兜底（池不因 AI 无匹配而空）
        assertThat(poolNames).hasSizeGreaterThanOrEqualTo(8);
    }

    @Test
    void insufficientHotelsTriggerSearchAndMergeWithoutCoordinates() throws Exception {
        // AI 精挑：仅 2 家（低于下限 6）→ 确定性触发联网检索
        when(hotelAgent.select(anyString(), anyString())).thenReturn(
                Result.<String>builder()
                        .content("{\"items\":[{\"hotelId\":1},{\"hotelId\":2}],"
                                + "\"advice\":\"符合预算的酒店较少，建议扩大范围\"}")
                        .tokenUsage(new TokenUsage(1, 1))
                        .build());
        // 联网检索：1 条合法（无坐标，网搜行常态） + 1 条价格非法
        when(searchClient.search(anyString(), anyString())).thenReturn(
                new DashScopeSearchClient.SearchResult(
                        "{\"items\":["
                                + "{\"name\":\"西湖民宿\",\"pricePerNight\":\"350\",\"level\":\"舒适\","
                                + "\"tags\":\"近西湖,安静\",\"address\":\"西湖区北山街\",\"why\":\"近西湖性价比高\"},"
                                + "{\"name\":\"天价酒店\",\"pricePerNight\":\"999999\",\"level\":\"豪华\","
                                + "\"tags\":\"豪华\",\"address\":\"市中心\",\"why\":\"x\"}"
                                + "]}",
                        100, 50));

        TravelState st = state();
        svc.generateHotels(st);

        verify(searchClient, times(1)).search(anyString(), anyString());
        assertThat(st.getWebHotelCandidates()).hasSize(1);
        verify(hotelMapper, times(1)).insert(any(Hotel.class));
        // 审计 place_type=HOTEL：1 ACCEPT + 1 REJECT
        ArgumentCaptor<WebFoodAudit> auditCaptor = ArgumentCaptor.forClass(WebFoodAudit.class);
        verify(auditMapper, times(2)).insert(auditCaptor.capture());
        assertThat(auditCaptor.getAllValues()).allSatisfy(a ->
                assertThat(a.getPlaceType()).isEqualTo("HOTEL"));
        // 网搜酒店并入候选池；无坐标 → 距离为 null（不 NPE、不参与距离排序）
        List<String> poolNames = st.getHotelPool().stream().map(c -> c.getName()).toList();
        assertThat(poolNames).contains("西湖民宿");
        assertThat(poolNames).doesNotContain("天价酒店");
        assertThat(st.getHotelPool()).filteredOn(c -> "西湖民宿".equals(c.getName()))
                .allSatisfy(c -> assertThat(c.getDistanceToCenter()).isNull());
        assertThat(st.getCandidateAdvice()).contains("已联网检索扩充 1 家");
    }

    @Test
    void failedSearchDoesNotBlockRetryForSameAttractionRequest() throws Exception {
        when(attractionAgent.select(anyString(), anyString(), anyInt())).thenReturn(
                Result.<String>builder()
                        .content("{\"items\":[{\"attractionId\":1}],\"advice\":\"仅1个符合\"}")
                        .tokenUsage(new TokenUsage(1, 1))
                        .build());
        when(searchClient.search(anyString(), anyString()))
                .thenThrow(new RuntimeException("HttpTimeoutException: request timed out"));

        TravelState st = state();
        svc.generateAttractions(st);
        svc.generateAttractions(st);

        // 两次确认同需求：两次都尝试联网检索（失败不阻断重试）
        verify(searchClient, times(2)).search(anyString(), anyString());
        // 失败时 advice 如实提示，不假装列表已扩充
        assertThat(st.getCandidateAdvice()).contains("联网检索暂不可用");
    }
}
