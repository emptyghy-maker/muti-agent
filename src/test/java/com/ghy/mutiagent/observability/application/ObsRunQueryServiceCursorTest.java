package com.ghy.mutiagent.observability.application;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 游标编解码为包内实现细节，测试与其同包以直接验证往返一致性 */
class ObsRunQueryServiceCursorTest {

    @Test
    void roundTripPreservesParts() {
        LocalDateTime now = LocalDateTime.now();
        String cursor = ObsRunQueryService.encodeCursor(now, "op-x");
        String[] parts = ObsRunQueryService.decodeCursor(cursor);
        assertEquals(2, parts.length);
        assertEquals("op-x", parts[1]);
    }

    @Test
    void invalidCursorRejected() {
        assertThrows(IllegalArgumentException.class, () -> ObsRunQueryService.decodeCursor("not-base64"));
        assertThrows(IllegalArgumentException.class, () -> ObsRunQueryService.decodeCursor(""));
    }
}
