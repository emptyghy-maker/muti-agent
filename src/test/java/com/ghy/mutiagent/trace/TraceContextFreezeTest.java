package com.ghy.mutiagent.trace;

import com.ghy.mutiagent.service.TimeSource;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * S12 结束时间冻结：首次 finish 原子固定终态，重复读取 duration 恒为终态值，
 * 重复 finish 不覆盖已确认终态、不追加重复终态事件。
 */
class TraceContextFreezeTest {

    /** 可控单调时钟（ms 粒度） */
    private static final class ControlledClock implements TimeSource {
        long nowMs;

        @Override
        public long nanoTime() {
            return nowMs * 1_000_000L;
        }

        void advance(long ms) {
            nowMs += ms;
        }
    }

    @Test
    void durationFrozenAfterFinishAndSecondFinishIsIdempotent() {
        ControlledClock clock = new ControlledClock();
        TraceContext ctx = new TraceContext("s", "q", clock);
        clock.advance(100);
        ctx.finish("SUCCESS");
        assertEquals(100, ctx.getTotalDurationMs());
        clock.advance(5000);
        assertEquals(100, ctx.getTotalDurationMs(), "finish 后 duration 必须冻结");
        ctx.finish("FAILED");
        assertEquals(100, ctx.getTotalDurationMs());
        assertEquals("SUCCESS", ctx.getStatus(), "重复 finish 不得覆盖已确认终态");
        assertEquals(1, ctx.terminalEventCount(), "重复 finish 不得追加重复终态事件");
    }

    @Test
    void liveDurationBeforeFinish() {
        ControlledClock clock = new ControlledClock();
        TraceContext ctx = new TraceContext("s", "q", clock);
        clock.advance(50);
        assertEquals(50, ctx.getTotalDurationMs());
        assertEquals("RUNNING", ctx.getStatus());
        assertEquals(0, ctx.terminalEventCount());
    }
}
