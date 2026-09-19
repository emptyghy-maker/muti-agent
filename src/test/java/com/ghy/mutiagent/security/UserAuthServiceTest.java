package com.ghy.mutiagent.security;

import com.ghy.mutiagent.model.enums.Role;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserAuthServiceTest {

    private final UserAuthService service = new UserAuthService();

    @Test
    void shouldAuthenticateWithCorrectPassword() {
        assertThat(service.authenticate("admin", "admin123")).isPresent();
        assertThat(service.authenticate("analyst", "analyst123")).isPresent();
    }

    @Test
    void shouldFailWithWrongPassword() {
        assertThat(service.authenticate("admin", "wrong")).isEmpty();
    }

    @Test
    void shouldResolveRoleByUserId() {
        assertThat(service.roleOf(1L)).isEqualTo(Role.ADMIN);
        assertThat(service.roleOf(2L)).isEqualTo(Role.ANALYST);
        // 未知用户默认更严格的 ANALYST
        assertThat(service.roleOf(999L)).isEqualTo(Role.ANALYST);
    }
}
