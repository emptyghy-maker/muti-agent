package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.LocationConstraint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocationConstraintSupportTest {

    @Test
    void 解析新街口附近并识别景点与餐厅作用域() {
        LocationConstraint c = LocationConstraintSupport.resolve("南京",
                "情侣约会，在新街口附近，饭店要网红店有氛围感，景点要优美，适合拍照纪念",
                List.of(LocationConstraintSupport.ATTRACTION));

        assertThat(c).isNotNull();
        assertThat(c.getAnchorName()).isEqualTo("新街口");
        assertThat(c.getStatus()).isEqualTo(LocationConstraint.RESOLVED);
        assertThat(c.getRadiusKm()).isEqualTo(3.0);
        assertThat(c.getScopes()).containsExactly(
                LocationConstraintSupport.ATTRACTION, LocationConstraintSupport.FOOD);
    }

    @Test
    void 明确公里数覆盖默认半径() {
        LocationConstraint c = LocationConstraintSupport.resolve("南京",
                "景点安排在新街口附近，控制在2公里以内",
                List.of(LocationConstraintSupport.ATTRACTION));

        assertThat(c.getRadiusKm()).isEqualTo(2.0);
        assertThat(c.getScopes()).containsExactly(LocationConstraintSupport.ATTRACTION);
    }

    @Test
    void 远处景点被拒绝且近处景点返回可展示距离() {
        LocationConstraint c = LocationConstraintSupport.resolve("南京", "新街口附近",
                List.of(LocationConstraintSupport.ATTRACTION));

        assertThat(LocationConstraintSupport.matches(c, LocationConstraintSupport.ATTRACTION,
                "1912街区", null, 118.795, 32.055)).isTrue();
        assertThat(LocationConstraintSupport.matches(c, LocationConstraintSupport.ATTRACTION,
                "牛首山文化旅游区", null, 118.735, 31.911)).isFalse();
        assertThat(LocationConstraintSupport.distanceKm(c, LocationConstraintSupport.ATTRACTION,
                118.795, 32.055)).isBetween(1.0, 3.0);
    }

    @Test
    void 无坐标联网结果必须由地址明确证明位于锚点附近() {
        LocationConstraint c = LocationConstraintSupport.resolve("南京", "新街口附近的餐厅",
                List.of(LocationConstraintSupport.FOOD));

        assertThat(LocationConstraintSupport.matches(c, LocationConstraintSupport.FOOD,
                "约会餐厅", "新街口商圈", null, null)).isTrue();
        assertThat(LocationConstraintSupport.matches(c, LocationConstraintSupport.FOOD,
                "约会餐厅", "夫子庙商圈", null, null)).isFalse();
    }

    @Test
    void 未知地标保留为不可核实而不伪造坐标() {
        LocationConstraint c = LocationConstraintSupport.resolve("南京", "火星广场附近",
                List.of(LocationConstraintSupport.ATTRACTION));

        assertThat(c.getStatus()).isEqualTo(LocationConstraint.UNRESOLVED);
        assertThat(c.getLng()).isNull();
        assertThat(LocationConstraintSupport.summary(c)).contains("无法确定");
    }

    @Test
    void 南京邮电大学三牌楼小区口误会归一为三牌楼校区() {
        LocationConstraint c = LocationConstraintSupport.resolve("南京",
                "南京邮电大学三牌楼小区附近，适合情侣约会的景点，饭店选小吃即可，最好带点特色的",
                List.of(LocationConstraintSupport.ATTRACTION));

        assertThat(c).isNotNull();
        assertThat(c.getAnchorName()).isEqualTo("南京邮电大学三牌楼校区");
        assertThat(c.getStatus()).isEqualTo(LocationConstraint.RESOLVED);
        assertThat(c.getLng()).isEqualTo(118.770844);
        assertThat(c.getLat()).isEqualTo(32.081113);
        assertThat(c.getScopes()).containsExactly(
                LocationConstraintSupport.ATTRACTION, LocationConstraintSupport.FOOD);
        assertThat(LocationConstraintSupport.summary(c))
                .isEqualTo("南京邮电大学三牌楼校区 3 公里范围内");
    }
}
