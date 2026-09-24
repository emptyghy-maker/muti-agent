package com.ghy.mutiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.agent.AttractionAgent;
import com.ghy.mutiagent.agent.FoodAgent;
import com.ghy.mutiagent.agent.HotelAgent;
import com.ghy.mutiagent.config.CandidateFoodConfig;
import com.ghy.mutiagent.model.AttractionCandidate;
import com.ghy.mutiagent.model.LocationConstraint;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.repository.entity.Attraction;
import com.ghy.mutiagent.repository.mapper.AttractionMapper;
import com.ghy.mutiagent.repository.mapper.HotelMapper;
import com.ghy.mutiagent.repository.mapper.RestaurantMapper;
import com.ghy.mutiagent.rule.LocationConstraintSupport;
import com.ghy.mutiagent.trace.TraceService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CandidateLocationConstraintTest {

    @Test
    void 景点召回先过滤新街口三公里范围且返回距离() {
        AttractionMapper attractionMapper = mock(AttractionMapper.class);
        when(attractionMapper.selectList(any())).thenReturn(List.of(
                attraction(1L, "1912街区", 118.795, 32.055, "夜景,情侣,打卡"),
                attraction(2L, "牛首山文化旅游区", 118.735, 31.911, "网红,打卡,出片")));
        CandidateService service = new CandidateService(attractionMapper,
                mock(RestaurantMapper.class), mock(HotelMapper.class),
                mock(AttractionAgent.class), mock(FoodAgent.class), mock(HotelAgent.class),
                mock(TraceService.class), mock(UsageService.class),
                new ObjectMapper(), new CandidateFoodConfig());
        TravelState state = new TravelState();
        state.setSessionId("loc-attraction");
        state.setDestinationId(1L);
        state.setDestinationName("南京");
        LocationConstraint constraint = LocationConstraintSupport.resolve("南京",
                "景点在新街口附近", List.of(LocationConstraintSupport.ATTRACTION));
        state.setLocationConstraint(constraint);

        service.generateAttractions(state);

        assertThat(state.getAttractionPool()).extracting(AttractionCandidate::getName)
                .containsExactly("1912街区");
        assertThat(state.getAttractionPool().get(0).getDistanceToAnchor()).isBetween(1.0, 3.0);
        assertThat(state.getCandidateAdvice()).contains("新街口 3 公里范围内", "确定性过滤");
    }

    private static Attraction attraction(long id, String name, double lng, double lat, String tags) {
        Attraction a = new Attraction();
        a.setId(id);
        a.setDestinationId(1L);
        a.setName(name);
        a.setCategory("打卡拍照");
        a.setFeatures("适合拍照");
        a.setTags(tags);
        a.setIntensity(1);
        a.setSuggestHours(2.0);
        a.setTicketPrice(BigDecimal.ZERO);
        a.setLng(lng);
        a.setLat(lat);
        a.setRating(4.6);
        a.setOpenTime("全天");
        a.setStatus(1);
        return a;
    }
}
