package com.ghy.mutiagent.service;

import com.ghy.mutiagent.repository.entity.PoiRecommendEvent;
import com.ghy.mutiagent.repository.entity.Restaurant;
import com.ghy.mutiagent.repository.entity.WebFoodAudit;
import com.ghy.mutiagent.repository.mapper.PoiRecommendEventMapper;
import com.ghy.mutiagent.repository.mapper.RestaurantMapper;
import com.ghy.mutiagent.repository.mapper.WebFoodAuditMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * POI 推荐计数与自动晋升（功能隔离组件）：
 * 幂等计数（同会话同店同事件一次）、网搜店限定、双阈值晋升（推荐3/勾选2）、
 * 质量门槛不齐不晋升（继续计数）、复用查询新鲜度。
 */
@ExtendWith(MockitoExtension.class)
class FoodPromotionServiceTest {

    @Mock
    private PoiRecommendEventMapper eventMapper;
    @Mock
    private RestaurantMapper restaurantMapper;
    @Mock
    private WebFoodAuditMapper auditMapper;

    private FoodPromotionService svc;

    @BeforeEach
    void setUp() {
        svc = new FoodPromotionService(eventMapper, restaurantMapper);
        ReflectionTestUtils.setField(svc, "auditMapper", auditMapper);
        // 纯单元进程无 Spring 配置注入：显式按生产默认口径设定阈值
        ReflectionTestUtils.setField(svc, "recommendThreshold", 3);
        ReflectionTestUtils.setField(svc, "selectThreshold", 2);
        ReflectionTestUtils.setField(svc, "reuseFreshnessDays", 90);
    }

    private Restaurant web(long id, int rec, int sel) {
        Restaurant r = new Restaurant();
        r.setId(id);
        r.setDestinationId(1L);
        r.setName("网搜店" + id);
        r.setCuisine("本地菜");
        r.setAvgPrice(BigDecimal.valueOf(80));
        r.setRating(4.5);
        r.setStatus(1);
        r.setSource(FoodPromotionService.SOURCE_WEB_SEARCH);
        r.setTags("本地菜,氛围");
        r.setAddress("某路1号");
        r.setLng(120.6);
        r.setLat(31.3);
        r.setRecommendCount(rec);
        r.setSelectCount(sel);
        return r;
    }

    @Test
    void 同一会话同一店同一事件只计一次() {
        when(restaurantMapper.selectById(9L)).thenReturn(web(9, 0, 0));
        when(eventMapper.insert(any(PoiRecommendEvent.class))).thenReturn(1)
                .thenThrow(new DuplicateKeyException("dup"));
        when(restaurantMapper.update(isNull(), any())).thenReturn(1);

        assertThat(svc.recordRecommend("s1", 9L, 1L)).isTrue();
        assertThat(svc.recordRecommend("s1", 9L, 1L)).isFalse(); // 唯一键去重：不再计数

        verify(eventMapper, times(2)).insert(any(PoiRecommendEvent.class));
        verify(restaurantMapper, times(1)).update(isNull(), any()); // 计数只 +1 一次
    }

    @Test
    void 知识库店不计事件() {
        Restaurant kb = web(9, 0, 0);
        kb.setSource("KB");
        when(restaurantMapper.selectById(9L)).thenReturn(kb);

        assertThat(svc.recordRecommend("s1", 9L, 1L)).isFalse();
        assertThat(svc.recordSelect("s1", 9L, 1L)).isFalse();
        verify(eventMapper, never()).insert(any(PoiRecommendEvent.class));
    }

    @Test
    void 推荐次数达到阈值自动晋升() {
        when(restaurantMapper.selectById(9L)).thenReturn(web(9, 3, 0)); // 3 个不同会话推荐过
        when(restaurantMapper.update(isNull(), any())).thenReturn(1);

        svc.maybePromote("s1", 9L);

        verify(restaurantMapper, times(1)).update(isNull(), any()); // 晋升更新
        verify(auditMapper).insert(argThat((WebFoodAudit a) -> "PROMOTE".equals(a.getAction())));
    }

    @Test
    void 勾选次数达到阈值自动晋升() {
        when(restaurantMapper.selectById(9L)).thenReturn(web(9, 0, 2)); // 2 个不同会话勾选过
        when(restaurantMapper.update(isNull(), any())).thenReturn(1);

        svc.maybePromote("s1", 9L);

        verify(restaurantMapper, times(1)).update(isNull(), any());
        verify(auditMapper).insert(argThat((WebFoodAudit a) -> "PROMOTE".equals(a.getAction())));
    }

    @Test
    void 质量门槛不齐不晋升继续计数() {
        Restaurant r = web(9, 3, 0);
        r.setTags(null); // 网搜店常见缺 tags：不晋升
        when(restaurantMapper.selectById(9L)).thenReturn(r);

        svc.maybePromote("s1", 9L);

        verify(restaurantMapper, never()).update(isNull(), any());
        verify(auditMapper).insert(argThat((WebFoodAudit a) -> "PROMOTE_SKIPPED".equals(a.getAction())
                && "MISSING_TAGS".equals(a.getRejectReason())));
    }

    @Test
    void 阈值未到不晋升() {
        when(restaurantMapper.selectById(9L)).thenReturn(web(9, 1, 0));

        svc.maybePromote("s1", 9L);

        verify(restaurantMapper, never()).update(isNull(), any());
        verify(auditMapper, never()).insert(any(WebFoodAudit.class));
    }

    @Test
    void 复用查询返回近期被推荐过的网搜店() {
        when(restaurantMapper.selectList(any())).thenReturn(List.of(web(9, 1, 0)));

        List<Restaurant> list = svc.reusableWebRestaurants(1L, LocalDateTime.now());

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getSource()).isEqualTo(FoodPromotionService.SOURCE_WEB_SEARCH);
    }
}
