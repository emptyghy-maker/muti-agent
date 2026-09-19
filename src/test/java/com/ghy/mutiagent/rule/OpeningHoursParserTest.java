package com.ghy.mutiagent.rule;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S04 契约测试：营业时间解析与整段游览覆盖。
 */
class OpeningHoursParserTest {

    @Test
    void 整段停留在一个开放区间内() {
        assertThat(OpeningHoursParser.covers("09:00-17:00", 9 * 60, 17 * 60))
                .isEqualTo(OpeningHoursParser.Status.COVERED);
    }

    @Test
    void 关门前到达但游览超时() {
        assertThat(OpeningHoursParser.covers("09:00-17:00", 16 * 60 + 30, 18 * 60 + 30))
                .isEqualTo(OpeningHoursParser.Status.CONFLICT);
    }

    @Test
    void 午间闭馆跨越() {
        assertThat(OpeningHoursParser.covers("09:00-12:00,14:00-17:00", 11 * 60, 13 * 60))
                .isEqualTo(OpeningHoursParser.Status.CONFLICT);
        assertThat(OpeningHoursParser.covers("09:00-12:00,14:00-17:00", 14 * 60, 17 * 60))
                .isEqualTo(OpeningHoursParser.Status.COVERED);
    }

    @Test
    void 未知营业信息() {
        assertThat(OpeningHoursParser.covers("周一闭馆，其他时间开放", 10 * 60, 12 * 60))
                .isEqualTo(OpeningHoursParser.Status.UNKNOWN);
        assertThat(OpeningHoursParser.covers(null, 10 * 60, 12 * 60))
                .isEqualTo(OpeningHoursParser.Status.UNKNOWN);
    }
}
