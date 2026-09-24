package com.ghy.mutiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.agent.AttractionAgent;
import com.ghy.mutiagent.agent.FoodAgent;
import com.ghy.mutiagent.agent.HotelAgent;
import com.ghy.mutiagent.config.CandidateFoodConfig;
import com.ghy.mutiagent.model.FoodCandidate;
import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.model.enums.TravelStage;
import com.ghy.mutiagent.repository.entity.Restaurant;
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
 * 联网美食扩充链路：覆盖不足 advice → 自动联网检索 → 确定性校验（恶意条目拒绝）→
 * 入库（参数化 insert）+ 逐条审计 → 通过校验的网搜店并入候选池优先展示。
 */
class WebFoodExpansionTest {

    private RestaurantMapper restaurantMapper;
    private FoodAgent foodAgent;
    private TraceService traceService;
    private UsageService usageService;
    private DashScopeSearchClient searchClient;
    private WebFoodAuditMapper auditMapper;
    private CandidateService svc;
    private List<Restaurant> webRows;

    @BeforeEach
    void setUp() {
        restaurantMapper = mock(RestaurantMapper.class);
        foodAgent = mock(FoodAgent.class);
        traceService = mock(TraceService.class);
        usageService = mock(UsageService.class);
        searchClient = mock(DashScopeSearchClient.class);
        auditMapper = mock(WebFoodAuditMapper.class);
        TraceContext ctx = mock(TraceContext.class);
        when(traceService.newTrace(anyString(), anyString())).thenReturn(ctx);
        svc = new CandidateService(
                mock(AttractionMapper.class), restaurantMapper, mock(HotelMapper.class),
                mock(AttractionAgent.class), foodAgent, mock(HotelAgent.class),
                traceService, usageService, new ObjectMapper(), new CandidateFoodConfig());
        ReflectionTestUtils.setField(svc, "dashScopeSearchClient", searchClient);
        ReflectionTestUtils.setField(svc, "searchModel", "qwen3.8-max");
        ReflectionTestUtils.setField(svc, "webFoodAuditMapper", auditMapper);

        // insert 模拟真实入库：回填 id 并记录到 webRows（供 webExpandedRestaurants 再查询）
        webRows = new ArrayList<>();
        AtomicInteger idSeq = new AtomicInteger(100);
        when(restaurantMapper.insert(any(Restaurant.class))).thenAnswer(inv -> {
            Restaurant r = inv.getArgument(0);
            r.setId((long) idSeq.incrementAndGet());
            webRows.add(r);
            return 1;
        });
        when(restaurantMapper.selectCount(any())).thenReturn(0L);
        // 所有 selectList 返回 KB 池 + 当前已入库的网搜行（合并列表；网搜行带 source=WEB_SEARCH，
        // 生产侧 webExpandedRestaurants 按 source 过滤，天然区分两类查询）
        when(restaurantMapper.selectList(any()))
                .thenAnswer(inv -> {
                    List<Restaurant> all = new ArrayList<>(kbPool());
                    all.addAll(webRows);
                    return all;
                });
    }

    private static List<Restaurant> kbPool() {
        List<Restaurant> list = new ArrayList<>();
        long id = 1;
        for (String[] row : new String[][]{
                {"本帮菜馆", "本地菜", "60", "4.8"}, {"园区火锅", "火锅", "90", "4.4"},
                {"苏式面馆", "面馆", "25", "4.6"}, {"湖边西餐", "西餐", "120", "4.3"},
                {"老茶馆", "茶馆", "30", "4.5"}, {"川香小馆", "川菜", "70", "4.2"},
                {"点心铺", "点心", "20", "4.4"}, {"烤鱼工坊", "烤鱼", "80", "4.1"}}) {
            Restaurant r = new Restaurant();
            r.setId(id++);
            r.setName(row[0]);
            r.setCuisine(row[1]);
            r.setAvgPrice(new java.math.BigDecimal(row[2]));
            r.setRating(Double.parseDouble(row[3]));
            r.setStatus(1);
            list.add(r);
        }
        return list;
    }

    private TravelState state() {
        TravelState st = new TravelState();
        st.setSessionId("m-web-expand");
        st.setUserId(1L);
        st.setDestinationId(1L);
        st.setDestinationName("杭州");
        st.setStage(TravelStage.FOODS);
        st.setExtraRequest("杭州园区，要有氛围感的饭店");
        TravelPreference p = new TravelPreference();
        p.setDays(2);
        st.setPreference(p);
        return st;
    }

    @Test
    void insufficientAdviceTriggersSearchAndMergesValidatedStoresIntoPool() throws Exception {
        // AI 精挑：池内有 1 家可用，但 advice 明示覆盖不足
        when(foodAgent.select(anyString(), anyString(), anyInt())).thenReturn(
                Result.<String>builder()
                        .content("{\"items\":[{\"restaurantId\":1}],"
                                + "\"advice\":\"池内园区店较少，无法凑齐15家，建议扩大搜索范围或选择其他区域\"}")
                        .tokenUsage(new TokenUsage(1, 1))
                        .build());
        // 联网检索：2 条合法 + 1 条恶意（<script>） + 1 条价格缺失
        when(searchClient.search(anyString(), anyString())).thenReturn(
                new DashScopeSearchClient.SearchResult(
                        "{\"items\":["
                                + "{\"name\":\"湖畔餐厅\",\"cuisine\":\"本地菜\",\"avgPrice\":\"80\","
                                + "\"address\":\"园区金鸡湖\",\"why\":\"湖边环境雅致\"},"
                                + "{\"name\":\"晚风小馆\",\"cuisine\":\"火锅\",\"avgPrice\":\"90\","
                                + "\"address\":\"园区星湖街\",\"why\":\"夜景氛围好\"},"
                                + "{\"name\":\"<script>alert(1)</script>\",\"cuisine\":\"本地菜\","
                                + "\"avgPrice\":\"50\",\"address\":\"x\",\"why\":\"x\"},"
                                + "{\"name\":\"无价餐厅\",\"cuisine\":\"本地菜\",\"avgPrice\":\"\","
                                + "\"address\":\"园区\",\"why\":\"x\"}"
                                + "]}",
                        100, 50));

        TravelState st = state();
        svc.generateFoods(st);

        // 覆盖不足 advice 触发了联网检索
        verify(searchClient, times(1)).search(anyString(), anyString());
        // 只接受 2 条合法条目
        assertThat(st.getWebFoodCandidates()).hasSize(2);
        // 合法条目入库（恶意与缺价条目不 insert）
        verify(restaurantMapper, times(2)).insert(any(Restaurant.class));
        // 审计：2 ACCEPT + 2 REJECT（恶意脚本与缺价）
        ArgumentCaptor<WebFoodAudit> auditCaptor = ArgumentCaptor.forClass(WebFoodAudit.class);
        verify(auditMapper, times(4)).insert(auditCaptor.capture());
        assertThat(auditCaptor.getAllValues()).filteredOn(a -> "ACCEPT".equals(a.getAction())).hasSize(2);
        assertThat(auditCaptor.getAllValues()).filteredOn(a -> "REJECT".equals(a.getAction())).hasSize(2);
        // 通过校验的网搜店并入候选池（可直接勾选）
        List<String> poolNames = st.getFoodPool().stream()
                .flatMap(g -> g.getRestaurants().stream())
                .map(FoodCandidate.FoodItem::getName).toList();
        assertThat(poolNames).contains("湖畔餐厅", "晚风小馆");
        assertThat(poolNames).doesNotContain("<script>alert(1)</script>", "无价餐厅");
    }

    @Test
    void regionShortfallWithoutMarkerWordsStillTriggersSearch() throws Exception {
        // 回归：advice 不带任何「覆盖不足」标记词（如「仅1家符合选址，其余补位」），
        // 只要 AI 精挑数量低于目标，就必须确定性触发联网检索，而不是依赖措辞
        when(foodAgent.select(anyString(), anyString(), anyInt())).thenReturn(
                Result.<String>builder()
                        .content("{\"items\":[{\"restaurantId\":1},{\"restaurantId\":2}],"
                                + "\"advice\":\"园区仅新梅华(金鸡湖店)符合选址，其余推荐观前/平江路高分本地菜以补氛围\"}")
                        .tokenUsage(new TokenUsage(1, 1))
                        .build());
        when(searchClient.search(anyString(), anyString())).thenReturn(
                new DashScopeSearchClient.SearchResult(
                        "{\"items\":["
                                + "{\"name\":\"湖畔餐厅\",\"cuisine\":\"本地菜\",\"avgPrice\":\"80\","
                                + "\"address\":\"园区金鸡湖\",\"why\":\"湖边环境雅致\"}"
                                + "]}",
                        100, 50));

        TravelState st = state();
        svc.generateFoods(st);

        // 无标记词也必须触发联网检索
        verify(searchClient, times(1)).search(anyString(), anyString());
        // 扩充说明追加到 advice，避免列表与说明错位
        assertThat(st.getCandidateAdvice()).contains("已联网检索扩充 1 家");
        assertThat(st.getCandidateAdvice()).contains("本轮优先匹配", "口味、人均消费、所在位置和餐次适配");
        assertThat(st.getCandidateAdvice()).doesNotContain("已按你的特殊要求完成 AI 重筛");
        assertThat(st.getCandidateAdvice()).doesNotContain("新梅华", "金鸡湖店");
        // 网搜店并入候选池
        List<String> poolNames = st.getFoodPool().stream()
                .flatMap(g -> g.getRestaurants().stream())
                .map(FoodCandidate.FoodItem::getName).toList();
        assertThat(poolNames).contains("湖畔餐厅");
    }

    @Test
    void failedSearchDoesNotBlockRetryForSameRequest() throws Exception {
        // 回归：搜索失败（如超时）不得登记去重键，否则同需求重试永远不再尝试搜索
        when(foodAgent.select(anyString(), anyString(), anyInt())).thenReturn(
                Result.<String>builder()
                        .content("{\"items\":[{\"restaurantId\":1}],\"advice\":\"仅1家符合选址\"}")
                        .tokenUsage(new TokenUsage(1, 1))
                        .build());
        when(searchClient.search(anyString(), anyString()))
                .thenThrow(new RuntimeException("HttpTimeoutException: request timed out"));

        TravelState st = state();
        svc.generateFoods(st);
        svc.generateFoods(st);

        // 两次确认同需求：两次都尝试联网检索（失败不阻断重试）
        verify(searchClient, times(2)).search(anyString(), anyString());
        // 失败时 advice 如实提示，不假装列表已扩充
        assertThat(st.getCandidateAdvice()).contains("联网检索暂不可用");
    }
}
