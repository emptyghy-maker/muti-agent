package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.enums.TravelStage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionControlIntentParserTest {

    @Test
    void 重新开始被识别为会话级指令() {
        assertThat(SessionControlIntentParser.parse("重新开始").type())
                .isEqualTo(SessionControlIntentParser.Type.RESTART);
        assertThat(SessionControlIntentParser.parse(" 从头开始！ ").type())
                .isEqualTo(SessionControlIntentParser.Type.RESTART);
        assertThat(SessionControlIntentParser.parse("重新开始规划").type())
                .isEqualTo(SessionControlIntentParser.Type.RESTART);
    }

    @Test
    void 可明确指定要重选的候选环节() {
        assertThat(SessionControlIntentParser.parse("我想重新选择景点").targetStage())
                .isEqualTo(TravelStage.ATTRACTIONS);
        assertThat(SessionControlIntentParser.parse("返回选餐厅").targetStage())
                .isEqualTo(TravelStage.FOODS);
        assertThat(SessionControlIntentParser.parse("酒店选错了").targetStage())
                .isEqualTo(TravelStage.HOTELS);
    }

    @Test
    void 较长普通需求不会被误判为控制指令() {
        assertThat(SessionControlIntentParser.parse("我想重新规划第二天的路线并保留第一天所有景点").type())
                .isEqualTo(SessionControlIntentParser.Type.NONE);
    }
}
