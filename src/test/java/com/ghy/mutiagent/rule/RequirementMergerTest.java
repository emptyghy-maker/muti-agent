package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.RequirementSnapshot;
import com.ghy.mutiagent.model.TravelState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S02 契约测试：需求合并与撤销。
 * 完全相同的约束去重；明确撤销只作用于目标 key；extraRequest 为累积渲染摘要。
 */
class RequirementMergerTest {

    private final RulePreferenceParser parser = new RulePreferenceParser();

    private TravelState state() {
        TravelState st = new TravelState();
        st.setSessionId("m-s2");
        st.setUserId(1L);
        return st;
    }

    @Test
    void 三轮需求独立撤销只影响目标() {
        TravelState st = state();
        RequirementMerger.mergeInto(st, parser.parseResult("带老人，行动不便", "specialRequests", st.getPreference()));
        RequirementMerger.mergeInto(st, parser.parseResult("不能爬山", "specialRequests", st.getPreference()));
        RequirementMerger.mergeInto(st, parser.parseResult("不用照顾老人了", "specialRequests", st.getPreference()));

        RequirementSnapshot snap = st.getRequirementSnapshot();
        assertThat(RequirementMerger.isActive(snap, "elderBackground")).as("撤销老人背景后不再生效").isFalse();
        assertThat(RequirementMerger.isActive(snap, "avoidClimbing")).as("独立确认的不能爬山不受影响").isTrue();
        assertThat(snap.getConstraints()).anySatisfy(c -> {
            assertThat(c.getKey()).isEqualTo("elderBackground");
            assertThat(c.getStatus()).isEqualTo(RequirementMerger.REVOKED);
        });
    }

    @Test
    void 相同约束去重且revision只增一次() {
        TravelState st = state();
        RequirementMerger.mergeInto(st, parser.parseResult("不要爬山", "specialRequests", st.getPreference()));
        int rev = st.getRequirementSnapshot().getRevision();
        RequirementMerger.mergeInto(st, parser.parseResult("不要爬山", "specialRequests", st.getPreference()));
        assertThat(st.getRequirementSnapshot().getConstraints()).hasSize(1);
        assertThat(st.getRequirementSnapshot().getRevision()).isEqualTo(rev);
    }

    @Test
    void 摘要累积不覆盖() {
        TravelState st = state();
        RequirementMerger.mergeInto(st, parser.parseResult("带老人，减少步行", "specialRequests", st.getPreference()));
        RequirementMerger.mergeInto(st, parser.parseResult("还想看夜景", "specialRequests", st.getPreference()));
        String extra = RequirementMerger.renderExtra(st);
        assertThat(extra).contains("老人", "夜景", "减少步行");
    }

    @Test
    void 摘要包含未解析残余原文() {
        TravelState st = state();
        RequirementMerger.mergeInto(st, parser.parseResult("想住能看极光的玻璃屋", "specialRequests", st.getPreference()));
        String extra = RequirementMerger.renderExtra(st);
        assertThat(extra).contains("极光", "玻璃屋");
    }
}
