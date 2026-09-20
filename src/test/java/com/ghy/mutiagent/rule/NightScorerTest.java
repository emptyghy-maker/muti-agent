package com.ghy.mutiagent.rule;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 夜景系数（确定性标签加权）：多个夜景景点时选系数最高者进入夜晚时段 */
class NightScorerTest {

    @Test
    void nightTagWeightsAddUp() {
        assertThat(NightScorer.score("夜景,湖景")).isEqualTo(3);
        assertThat(NightScorer.score("夜景,音乐喷泉")).isEqualTo(5);
        assertThat(NightScorer.score("夜景,音乐喷泉,摩天轮")).isEqualTo(6);
        assertThat(NightScorer.score("湖景,情侣")).isZero();
    }

    @Test
    void isNightOnlyWhenScorePositive() {
        assertThat(NightScorer.isNight("夜景,湖景")).isTrue();
        assertThat(NightScorer.isNight("音乐喷泉")).isTrue();
        assertThat(NightScorer.isNight("")).isFalse();
        assertThat(NightScorer.isNight(null)).isFalse();
        assertThat(NightScorer.isNight("湖景,园林")).isFalse();
    }
}
