package com.ghy.mutiagent.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeoUtilsTest {

    @Test
    void 相同坐标距离为0() {
        assertThat(GeoUtils.distanceKm(118.788, 32.023, 118.788, 32.023)).isEqualTo(0.0);
    }

    @Test
    void 南京夫子庙到老门东约一公里() {
        double d = GeoUtils.distanceKm(118.788, 32.023, 118.784, 32.014);
        assertThat(d).isBetween(0.9, 1.2);
    }

    @Test
    void 北京到上海约一千公里() {
        double d = GeoUtils.distanceKm(116.407, 39.904, 121.474, 31.230);
        assertThat(d).isBetween(1000.0, 1150.0);
    }
}
