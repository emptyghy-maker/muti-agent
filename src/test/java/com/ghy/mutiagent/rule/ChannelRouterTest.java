package com.ghy.mutiagent.rule;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 三通道独立对话的确定性路由：单一通道命中 → 目标通道；零命中/多通道命中 → null（沿用当前环节） */
class ChannelRouterTest {

    @Test
    void 单一通道关键词命中路由到对应通道() {
        assertThat(ChannelRouter.route("酒店要便宜一点，最好近地铁")).isEqualTo(ChannelRouter.CHANNEL_HOTEL);
        assertThat(ChannelRouter.route("美食要辣的，人均50以内")).isEqualTo(ChannelRouter.CHANNEL_FOOD);
        assertThat(ChannelRouter.route("景点想安排夜景，门票别太贵")).isEqualTo(ChannelRouter.CHANNEL_ATTRACTION);
        assertThat(ChannelRouter.route("这家民宿怎么样")).isEqualTo(ChannelRouter.CHANNEL_HOTEL);
        assertThat(ChannelRouter.route("想吃本地小吃")).isEqualTo(ChannelRouter.CHANNEL_FOOD);
        assertThat(ChannelRouter.route("别安排爬山")).isEqualTo(ChannelRouter.CHANNEL_ATTRACTION);
    }

    @Test
    void 与环节无关的条件沿用当前环节() {
        assertThat(ChannelRouter.route("预算控制在1000以内")).isNull();
        assertThat(ChannelRouter.route("我们一共三个人")).isNull();
        assertThat(ChannelRouter.route("换一批")).isNull();
    }

    @Test
    void 多通道命中的整句沿用当前环节() {
        // 跨通道整句语义（如「酒店和美食都要便宜」）不做单通道误判
        assertThat(ChannelRouter.route("酒店和美食都要便宜")).isNull();
        assertThat(ChannelRouter.route("住宿附近的景点有哪些")).isNull();
    }

    @Test
    void 空消息返回null() {
        assertThat(ChannelRouter.route(null)).isNull();
        assertThat(ChannelRouter.route("   ")).isNull();
    }
}
