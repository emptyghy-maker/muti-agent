package com.ghy.mutiagent.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UsageChannelTest {

    @Test
    void 空渠道并入非空时取非空() {
        assertThat(UsageChannel.merge(null, UsageChannel.KB)).isEqualTo(UsageChannel.KB);
        assertThat(UsageChannel.merge("", UsageChannel.CACHE)).isEqualTo(UsageChannel.CACHE);
    }

    @Test
    void 传入空渠道时保持原渠道() {
        assertThat(UsageChannel.merge(UsageChannel.KB, null)).isEqualTo(UsageChannel.KB);
        assertThat(UsageChannel.merge(UsageChannel.AGENT, "")).isEqualTo(UsageChannel.AGENT);
    }

    @Test
    void 高优先级渠道覆盖低优先级() {
        assertThat(UsageChannel.merge(UsageChannel.KB, UsageChannel.AGENT)).isEqualTo(UsageChannel.AGENT);
        assertThat(UsageChannel.merge(UsageChannel.CACHE, UsageChannel.RULE_FALLBACK))
                .isEqualTo(UsageChannel.RULE_FALLBACK);
        assertThat(UsageChannel.merge(UsageChannel.OP, UsageChannel.CACHE)).isEqualTo(UsageChannel.CACHE);
    }

    @Test
    void 低优先级渠道不覆盖高优先级() {
        assertThat(UsageChannel.merge(UsageChannel.AGENT, UsageChannel.RULE_FALLBACK)).isEqualTo(UsageChannel.AGENT);
        assertThat(UsageChannel.merge(UsageChannel.RULE_FALLBACK, UsageChannel.KB)).isEqualTo(UsageChannel.RULE_FALLBACK);
        assertThat(UsageChannel.merge(UsageChannel.KB, UsageChannel.CACHE)).isEqualTo(UsageChannel.KB);
    }
}
