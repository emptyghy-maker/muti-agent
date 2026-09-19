package com.ghy.mutiagent.service.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.repository.entity.Itinerary;
import com.ghy.mutiagent.repository.mapper.AttractionMapper;
import com.ghy.mutiagent.repository.mapper.HotelMapper;
import com.ghy.mutiagent.repository.mapper.ItineraryMapper;
import com.ghy.mutiagent.repository.mapper.RestaurantMapper;
import com.ghy.mutiagent.security.AuthenticatedUser;
import com.ghy.mutiagent.service.TravelSessionService;
import com.ghy.mutiagent.service.UsageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * P0-S01 契约测试：路径查询的归属。
 * - 已落库行程：不存在/他人行程统一 404，且归属校验先于行程内容解析；
 * - 会话内行程：会话归属失败直接透出（404）。
 */
@ExtendWith(MockitoExtension.class)
class RouteOwnershipTest {

    @Mock
    private TravelSessionService sessionService;
    @Mock
    private AttractionMapper attractionMapper;
    @Mock
    private RestaurantMapper restaurantMapper;
    @Mock
    private HotelMapper hotelMapper;
    @Mock
    private ItineraryMapper itineraryMapper;
    @Mock
    private RoutePlanner routePlanner;
    @Mock
    private UsageService usageService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private final AuthenticatedUser analyst = new AuthenticatedUser(1L, "analyst", "ANALYST");

    private RouteService service;

    @BeforeEach
    void setUp() {
        service = new RouteService(sessionService, attractionMapper, restaurantMapper, hotelMapper,
                itineraryMapper, routePlanner, objectMapper, usageService);
    }

    @Test
    void shouldHideMissingItineraryAs404() {
        when(itineraryMapper.selectById(10L)).thenReturn(null);
        assertThatThrownBy(() -> service.routeByItinerary(analyst, 10L, 1, 1, 2))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.RESOURCE_NOT_FOUND.getCode()));
    }

    @Test
    void shouldHideForeignItineraryAs404() {
        Itinerary it = new Itinerary();
        it.setId(10L);
        it.setUserId(2L);
        it.setPlanJson("{\"days\":[]}");
        when(itineraryMapper.selectById(10L)).thenReturn(it);
        assertThatThrownBy(() -> service.routeByItinerary(analyst, 10L, 1, 1, 2))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.RESOURCE_NOT_FOUND.getCode()));
    }

    @Test
    void shouldCheckOwnershipBeforeParsingPlan() {
        // 所有者行程但计划内容损坏：报参数错误而不是 404，证明归属检查已先通过
        Itinerary it = new Itinerary();
        it.setId(10L);
        it.setUserId(1L);
        it.setPlanJson("{{{ 不是合法 json");
        when(itineraryMapper.selectById(10L)).thenReturn(it);
        assertThatThrownBy(() -> service.routeByItinerary(analyst, 10L, 1, 1, 2))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.PARAM_ERROR.getCode()));
    }

    @Test
    void shouldPropagateSessionOwnershipFailure() {
        when(sessionService.loadOwned("s1", 1L))
                .thenThrow(new BizException(ResultCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> service.route(analyst, "s1", 1, 1, 2))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.RESOURCE_NOT_FOUND.getCode()));
    }
}
