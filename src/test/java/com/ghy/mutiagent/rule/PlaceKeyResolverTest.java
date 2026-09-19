package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.PlaceKey;
import com.ghy.mutiagent.model.PlaceType;
import com.ghy.mutiagent.model.PlanNode;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S05 契约测试：节点 → 复合地点键的唯一转换规则。
 * 交通节点/无实体/非法 ID 一律 empty，不构造 id=0 的假键。
 */
class PlaceKeyResolverTest {

    private static PlanNode node(String type, Long placeId) {
        PlanNode n = new PlanNode();
        n.setType(type);
        n.setPlaceId(placeId);
        return n;
    }

    @Test
    void 景点节点解析为ATTRACTION() {
        assertThat(PlaceKeyResolver.fromNode(node("attraction", 1L)))
                .isEqualTo(Optional.of(PlaceKey.of(PlaceType.ATTRACTION, 1L)));
    }

    @Test
    void 休息点解析为景点实体() {
        assertThat(PlaceKeyResolver.fromNode(node("rest", 1L)))
                .isEqualTo(Optional.of(PlaceKey.of(PlaceType.ATTRACTION, 1L)));
    }

    @Test
    void 餐厅节点解析为RESTAURANT() {
        assertThat(PlaceKeyResolver.fromNode(node("restaurant", 1L)))
                .isEqualTo(Optional.of(PlaceKey.of(PlaceType.RESTAURANT, 1L)));
    }

    @Test
    void 酒店节点解析为HOTEL() {
        assertThat(PlaceKeyResolver.fromNode(node("hotel", 1L)))
                .isEqualTo(Optional.of(PlaceKey.of(PlaceType.HOTEL, 1L)));
    }

    @Test
    void 交通节点没有实体() {
        assertThat(PlaceKeyResolver.fromNode(node("transport", null))).isEmpty();
    }

    @Test
    void 无地点ID没有实体() {
        assertThat(PlaceKeyResolver.fromNode(node("attraction", null))).isEmpty();
    }

    @Test
    void 非法ID没有实体() {
        assertThat(PlaceKeyResolver.fromNode(node("attraction", 0L))).isEmpty();
        assertThat(PlaceKeyResolver.fromNode(node("attraction", -3L))).isEmpty();
    }
}
