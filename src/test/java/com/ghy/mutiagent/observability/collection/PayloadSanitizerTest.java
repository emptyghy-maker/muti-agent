package com.ghy.mutiagent.observability.collection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 载荷净化：脱敏 → 截断 TRUNCATED → 摘要哈希；同步路径轻量 */
class PayloadSanitizerTest {

    @Test
    void sensitiveContentMaskedAndMarked() {
        PayloadSanitizer.Sanitized s = PayloadSanitizer.sanitize(
                "token ABCDEF0123456789 phone 13812345678 mail a@b.com ok", 1024);
        assertTrue(s.content().contains("[REDACTED]"));
        assertTrue(s.content().contains("ok"));
        assertEquals("REDACTED", s.status());
        assertEquals(16, s.digest().length());
    }

    @Test
    void oversizedContentTruncated() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            big.append('x');
        }
        PayloadSanitizer.Sanitized s = PayloadSanitizer.sanitize(big.toString(), 100);
        assertEquals("TRUNCATED", s.status());
        assertTrue(s.content().endsWith("…[TRUNCATED]"));
        assertEquals(5000, s.sizeBytes());
    }

    @Test
    void cleanContentStaysFull() {
        PayloadSanitizer.Sanitized s = PayloadSanitizer.sanitize("普通文本", 1024);
        assertEquals("FULL", s.status());
        assertEquals("普通文本", s.content());
    }

    @Test
    void digestDiffersForDifferentContent() {
        assertNotEquals(PayloadSanitizer.sha256("a"), PayloadSanitizer.sha256("b"));
    }
}
