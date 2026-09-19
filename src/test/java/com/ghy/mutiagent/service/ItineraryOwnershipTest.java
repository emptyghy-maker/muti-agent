package com.ghy.mutiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.agent.ItineraryAgent;
import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.model.FeedbackRequest;
import com.ghy.mutiagent.repository.entity.Itinerary;
import com.ghy.mutiagent.repository.entity.ItineraryFeedback;
import com.ghy.mutiagent.repository.mapper.AttractionMapper;
import com.ghy.mutiagent.repository.mapper.DestinationMapper;
import com.ghy.mutiagent.repository.mapper.HotelMapper;
import com.ghy.mutiagent.repository.mapper.ItineraryFeedbackMapper;
import com.ghy.mutiagent.repository.mapper.ItineraryMapper;
import com.ghy.mutiagent.repository.mapper.RestaurantMapper;
import com.ghy.mutiagent.security.AuthenticatedUser;
import com.ghy.mutiagent.trace.TraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-S01 契约测试：行程归属。
 * - 详情/调整/评价对不存在、无所有者、他人行程统一 404；
 * - 对已归档行程发起调整 → 409（状态冲突）；
 * - 所有者本人可正常读取、评价。
 */
@ExtendWith(MockitoExtension.class)
class ItineraryOwnershipTest {

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

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private final AuthenticatedUser analyst = new AuthenticatedUser(1L, "analyst", "ANALYST");

    private ItineraryService service;

    @BeforeEach
    void setUp() {
        service = new ItineraryService(attractionMapper, restaurantMapper, hotelMapper, itineraryMapper,
                itineraryFeedbackMapper, destinationMapper, itineraryAgent, traceService, usageService,
                objectMapper, new ItineraryCommitService(itineraryMapper));
    }

    private Itinerary owned(Long userId, String status) {
        Itinerary it = new Itinerary();
        it.setId(10L);
        it.setUserId(userId);
        it.setStatus(status);
        it.setDestinationId(1L);
        it.setPreferenceJson("{}");
        it.setPlanJson("{\"days\":[]}");
        return it;
    }

    @Test
    void shouldHideMissingItineraryAs404() {
        when(itineraryMapper.selectById(10L)).thenReturn(null);
        assertThatThrownBy(() -> service.detail(analyst, 10L))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.RESOURCE_NOT_FOUND.getCode()));
    }

    @Test
    void shouldHideForeignItineraryAs404() {
        when(itineraryMapper.selectById(10L)).thenReturn(owned(2L, "ACTIVE"));
        assertThatThrownBy(() -> service.detail(analyst, 10L))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.RESOURCE_NOT_FOUND.getCode()));
    }

    @Test
    void shouldHideOwnerlessItineraryAs404() {
        when(itineraryMapper.selectById(10L)).thenReturn(owned(null, "ACTIVE"));
        assertThatThrownBy(() -> service.detail(analyst, 10L))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.RESOURCE_NOT_FOUND.getCode()));
    }

    @Test
    void shouldLetOwnerReadOwnItinerary() {
        when(itineraryMapper.selectById(10L)).thenReturn(owned(1L, "ACTIVE"));
        assertThat(service.detail(analyst, 10L).getId()).isEqualTo(10L);
    }

    @Test
    void shouldRejectAdjustOfArchivedItineraryAs409() {
        when(itineraryMapper.selectById(10L)).thenReturn(owned(1L, "ARCHIVED"));
        assertThatThrownBy(() -> service.adjust(analyst, 10L, "换个顺序"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.STATE_CONFLICT.getCode()));
    }

    @Test
    void shouldRejectAdjustOfForeignItineraryAs404() {
        when(itineraryMapper.selectById(10L)).thenReturn(owned(2L, "ACTIVE"));
        assertThatThrownBy(() -> service.adjust(analyst, 10L, "换个顺序"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.RESOURCE_NOT_FOUND.getCode()));
    }

    @Test
    void shouldRejectFeedbackOnForeignItineraryAs404() {
        when(itineraryMapper.selectById(10L)).thenReturn(owned(2L, "ACTIVE"));
        assertThatThrownBy(() -> service.submitFeedback(analyst, 10L, new FeedbackRequest()))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.RESOURCE_NOT_FOUND.getCode()));
    }

    @Test
    void shouldLetOwnerSubmitFeedback() {
        when(itineraryMapper.selectById(10L)).thenReturn(owned(1L, "ACTIVE"));
        service.submitFeedback(analyst, 10L, new FeedbackRequest());
        verify(itineraryFeedbackMapper).insert(any(ItineraryFeedback.class));
    }
}
