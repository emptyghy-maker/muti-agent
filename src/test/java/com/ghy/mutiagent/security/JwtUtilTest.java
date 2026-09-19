package com.ghy.mutiagent.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    private static final String SECRET = "this-is-a-long-test-secret-key-0123456789abcdef";
    private static final String OTHER_SECRET = "another-long-different-secret-key-abcdef0123456789";

    @Test
    void shouldRoundTrip() {
        JwtUtil jwt = new JwtUtil(SECRET, 3_600_000L);
        String token = jwt.generate(1L, "admin", "ADMIN");

        Claims claims = jwt.parse(token);
        assertThat(claims.getSubject()).isEqualTo("admin");
        assertThat(claims.get("uid", Long.class)).isEqualTo(1L);
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
    }

    @Test
    void shouldRejectTokenSignedWithDifferentKey() {
        JwtUtil jwt = new JwtUtil(SECRET, 3_600_000L);
        JwtUtil wrongKey = new JwtUtil(OTHER_SECRET, 3_600_000L);
        String token = jwt.generate(1L, "admin", "ADMIN");

        assertThatThrownBy(() -> wrongKey.parse(token)).isInstanceOf(JwtException.class);
    }
}
