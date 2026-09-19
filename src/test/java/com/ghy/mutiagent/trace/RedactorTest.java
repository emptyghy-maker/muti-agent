package com.ghy.mutiagent.trace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S12 脱敏：token/手机号/邮箱三个合成标记在所有出口共用同一口径。
 */
class RedactorTest {

    @Test
    void masksAllThreeMarkerKinds() {
        String raw = "token=TEST_SECRET_TOKEN_8D2 phone=13800138000 mail=person@example.invalid";
        String masked = Redactor.mask(raw);
        assertFalse(Redactor.containsSensitive(masked));
        assertTrue(masked.contains(Redactor.REDACTED));
    }

    @Test
    void leavesPlainTextUntouched() {
        assertEquals("普通说明文本", Redactor.mask("普通说明文本"));
        assertNull(Redactor.mask(null));
    }

    @Test
    void shortTokensAreNotMasked() {
        assertEquals("id 42 正常", Redactor.mask("id 42 正常"));
    }
}
