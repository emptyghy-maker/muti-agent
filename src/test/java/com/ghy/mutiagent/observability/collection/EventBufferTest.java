package com.ghy.mutiagent.observability.collection;

import com.ghy.mutiagent.observability.domain.ObsEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 有界事件缓冲：满时丢明细记 gap，不阻塞业务线程 */
class EventBufferTest {

    private static ObsEvent event(String id, long seq) {
        return new ObsEvent("obs-event-v1", id, "run-1", "op-1", "s1", null, "n1",
                "NODE_STARTED", 1L, 1L, "p", seq, 1L, "d", null, java.util.Map.of());
    }

    @Test
    void capacityBoundedWithGapCounting() {
        EventBuffer buffer = new EventBuffer(3);
        assertTrue(buffer.offer(event("e1", 1)));
        assertTrue(buffer.offer(event("e2", 2)));
        assertTrue(buffer.offer(event("e3", 3)));
        assertFalse(buffer.offer(event("e4", 4)));
        assertFalse(buffer.offer(event("e5", 5)));
        assertEquals(2, buffer.gapCount());
        assertEquals(3, buffer.size());
    }

    @Test
    void drainTakesUpToMaxAndGapResets() {
        EventBuffer buffer = new EventBuffer(4);
        for (int i = 0; i < 4; i++) {
            buffer.offer(event("e" + i, i));
        }
        buffer.offer(event("x", 9));
        List<ObsEvent> batch = buffer.drain(10);
        assertEquals(4, batch.size());
        assertEquals(1, buffer.takeGapCount());
        assertEquals(0, buffer.gapCount());
        assertEquals(0, buffer.size());
    }
}
