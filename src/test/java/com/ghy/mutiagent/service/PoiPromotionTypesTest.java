package com.ghy.mutiagent.service;

import com.ghy.mutiagent.repository.entity.Attraction;
import com.ghy.mutiagent.repository.entity.Hotel;
import com.ghy.mutiagent.repository.entity.PoiRecommendEvent;
import com.ghy.mutiagent.repository.entity.Restaurant;
import com.ghy.mutiagent.repository.entity.WebFoodAudit;
import com.ghy.mutiagent.repository.mapper.AttractionMapper;
import com.ghy.mutiagent.repository.mapper.HotelMapper;
import com.ghy.mutiagent.repository.mapper.PoiRecommendEventMapper;
import com.ghy.mutiagent.repository.mapper.RestaurantMapper;
import com.ghy.mutiagent.repository.mapper.WebFoodAuditMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * POI 晋升三通道对称（功能隔离扩展）：景点/酒店与美食同口径——
 * place_type 参与事件唯一键、推荐计数走各自表、晋升门槛 tags/address 齐备
 * （坐标门槛仅美食通道保留：网搜景点/酒店不采集坐标），审计带 place_type 留痕。
 */
@ExtendWith(MockitoExtension.class)
class PoiPromotionTypesTest {

    @Mock
    private PoiRecommendEventMapper eventMapper;
    @Mock
    private RestaurantMapper restaurantMapper;
    @Mock
    private AttractionMapper attractionMapper;
    @Mock
    private HotelMapper hotelMapper;
    @Mock
    private WebFoodAuditMapper auditMapper;

    private FoodPromotionService svc;

    @BeforeEach
    void setUp() {
        svc = new FoodPromotionService(eventMapper, restaurantMapper, attractionMapper, hotelMapper);
        ReflectionTestUtils.setField(svc, "auditMapper", auditMapper);
        ReflectionTestUtils.setField(svc, "recommendThreshold", 3);
        ReflectionTestUtils.setField(svc, "selectThreshold", 2);
        ReflectionTestUtils.setField(svc, "reuseFreshnessDays", 90);
    }

    private Attraction webAttraction(long id, int rec, int sel) {
        Attraction a = new Attraction();
        a.setId(id);
        a.setDestinationId(1L);
        a.setName("网搜景点" + id);
        a.setCategory("自然风光");
        a.setTags("夜景,户外");
        a.setAddress("某山脚下");
        a.setTicketPrice(BigDecimal.ZERO);
        a.setRating(4.5);
        a.setStatus(1);
        a.setSource(FoodPromotionService.SOURCE_WEB_SEARCH);
        a.setRecommendCount(rec);
        a.setSelectCount(sel);
        // 网搜景点无坐标：晋升门槛不得因此阻断
        return a;
    }

    private Hotel webHotel(long id, int rec, int sel) {
        Hotel h = new Hotel();
        h.setId(id);
        h.setDestinationId(1L);
        h.setName("网搜酒店" + id);
        h.setLevel("舒适");
        h.setTags("近地铁,安静");
        h.setAddress("某街10号");
        h.setPricePerNight(BigDecimal.valueOf(300));
        h.setRating(4.5);
        h.setStatus(1);
        h.setSource(FoodPromotionService.SOURCE_WEB_SEARCH);
        h.setRecommendCount(rec);
        h.setSelectCount(sel);
        return h;
    }

    @Test
    void 景点推荐达到阈值自动晋升且审计带类型() {
        when(attractionMapper.selectById(9L)).thenReturn(webAttraction(9, 3, 0));
        when(attractionMapper.update(isNull(), any())).thenReturn(1);

        svc.maybePromote(FoodPromotionService.TYPE_ATTRACTION, "s1", 9L);

        verify(attractionMapper, times(1)).update(isNull(), any());
        verify(restaurantMapper, never()).update(isNull(), any());
        verify(auditMapper).insert(org.mockito.ArgumentMatchers.argThat(
                (WebFoodAudit a) -> "PROMOTE".equals(a.getAction())
                        && FoodPromotionService.TYPE_ATTRACTION.equals(a.getPlaceType())
                        && Long.valueOf(9L).equals(a.getPlaceId())));
    }

    @Test
    void 景点无坐标仍可晋升标签地址齐备() {
        // 坐标门槛仅美食通道保留：网搜景点 tags/address 齐备即可晋升
        when(attractionMapper.selectById(9L)).thenReturn(webAttraction(9, 3, 0));
        when(attractionMapper.update(isNull(), any())).thenReturn(1);

        svc.maybePromote(FoodPromotionService.TYPE_ATTRACTION, "s1", 9L);

        verify(auditMapper).insert(org.mockito.ArgumentMatchers.argThat(
                (WebFoodAudit a) -> "PROMOTE".equals(a.getAction())));
        verify(auditMapper, never()).insert(org.mockito.ArgumentMatchers.argThat(
                (WebFoodAudit a) -> "PROMOTE_SKIPPED".equals(a.getAction())));
    }

    @Test
    void 酒店勾选达到阈值自动晋升() {
        when(hotelMapper.selectById(7L)).thenReturn(webHotel(7, 0, 2));
        when(hotelMapper.update(isNull(), any())).thenReturn(1);

        svc.maybePromote(FoodPromotionService.TYPE_HOTEL, "s2", 7L);

        verify(hotelMapper, times(1)).update(isNull(), any());
        verify(auditMapper).insert(org.mockito.ArgumentMatchers.argThat(
                (WebFoodAudit a) -> "PROMOTE".equals(a.getAction())
                        && FoodPromotionService.TYPE_HOTEL.equals(a.getPlaceType())));
    }

    @Test
    void 景点勾选事件带placeType计数走景点表() {
        when(attractionMapper.selectById(5L)).thenReturn(webAttraction(5, 0, 0));
        when(eventMapper.insert(any(PoiRecommendEvent.class))).thenReturn(1);
        when(attractionMapper.update(isNull(), any())).thenReturn(1);

        assertThat(svc.recordSelect(FoodPromotionService.TYPE_ATTRACTION, "s3", 5L, 1L)).isTrue();

        ArgumentCaptor<PoiRecommendEvent> ev = ArgumentCaptor.forClass(PoiRecommendEvent.class);
        verify(eventMapper).insert(ev.capture());
        assertThat(ev.getValue().getPlaceType()).isEqualTo(FoodPromotionService.TYPE_ATTRACTION);
        assertThat(ev.getValue().getEventType()).isEqualTo(FoodPromotionService.EVENT_SELECT);
        verify(attractionMapper, times(1)).update(isNull(), any());
        verify(restaurantMapper, never()).update(isNull(), any());
    }

    @Test
    void 美食通道坐标门槛保持不变() {
        Restaurant r = new Restaurant();
        r.setId(9L);
        r.setDestinationId(1L);
        r.setName("网搜店");
        r.setCuisine("本地菜");
        r.setTags("本地菜,氛围");
        r.setAddress("某路1号");
        r.setAvgPrice(BigDecimal.valueOf(80));
        r.setStatus(1);
        r.setSource(FoodPromotionService.SOURCE_WEB_SEARCH);
        r.setRecommendCount(3);
        r.setSelectCount(0);
        // 无坐标：美食通道不晋升（既有口径）
        when(restaurantMapper.selectById(9L)).thenReturn(r);

        svc.maybePromote("s1", 9L);

        verify(restaurantMapper, never()).update(isNull(), any());
        verify(auditMapper).insert(org.mockito.ArgumentMatchers.argThat(
                (WebFoodAudit a) -> "PROMOTE_SKIPPED".equals(a.getAction())
                        && "MISSING_COORDS".equals(a.getRejectReason())
                        && FoodPromotionService.TYPE_FOOD.equals(a.getPlaceType())));
    }

    @Test
    void 景点复用查询与仅装配美食时降级() {
        when(attractionMapper.selectList(any())).thenReturn(List.of(webAttraction(9, 1, 0)));

        assertThat(svc.reusableWebAttractions(1L, LocalDateTime.now())).hasSize(1);

        // 仅装配美食 Mapper（门禁/测试进程）：景点复用查询返回空，功能隔离
        FoodPromotionService foodOnly = new FoodPromotionService(eventMapper, restaurantMapper);
        assertThat(foodOnly.reusableWebAttractions(1L, LocalDateTime.now())).isEmpty();
        assertThat(foodOnly.reusableWebHotels(1L, LocalDateTime.now())).isEmpty();
    }
}
