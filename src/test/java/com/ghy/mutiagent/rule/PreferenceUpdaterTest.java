package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.model.TravelState;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C3 契约测试：餐次结构字段应用（三态标记 + 增量合并不互相覆盖）。
 */
class PreferenceUpdaterTest {

    private TravelState state() {
        TravelState st = new TravelState();
        st.setSessionId("m-up");
        st.setUserId(1L);
        return st;
    }

    @Test
    void 餐次顿数写入并标记确认() {
        TravelState st = state();
        PreferenceUpdater.apply(st, Map.of("lunchPerDay", "2", "dinnerPerDay", "1", "breakfastPerDay", "0"));
        TravelPreference.MealPlan mp = st.getPreference().getMealPlan();
        assertThat(mp.getLunchPerDay()).isEqualTo(2);
        assertThat(mp.getDinnerPerDay()).isEqualTo(1);
        assertThat(mp.getBreakfastPerDay()).isEqualTo(0);
        assertThat(st.getPreference().getFieldStates()).containsEntry("mealPlan", "CONFIRMED");
    }

    @Test
    void 小吃取舍合并时不覆盖已确认顿数() {
        TravelState st = state();
        PreferenceUpdater.apply(st, Map.of("lunchPerDay", "2"));
        PreferenceUpdater.apply(st, Map.of("snacksAllowed", "false"));
        TravelPreference.MealPlan mp = st.getPreference().getMealPlan();
        assertThat(mp.getLunchPerDay()).isEqualTo(2);
        assertThat(mp.getSnacksAllowed()).isFalse();
        assertThat(mp.getDinnerPerDay()).isNull();
    }

    @Test
    void 越界顿数被忽略且不产生餐次结构() {
        TravelState st = state();
        PreferenceUpdater.apply(st, Map.of("lunchPerDay", "9"));
        assertThat(st.getPreference().getMealPlan()).isNull();
        assertThat(st.getPreference().isMissing("mealPlan")).isTrue();
    }
}
