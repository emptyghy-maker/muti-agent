package com.ghy.mutiagent.security;

import com.ghy.mutiagent.model.enums.Role;
import org.springframework.stereotype.Component;

/**
 * 角色解析器：userId → Role，供 SQL 审查引擎按角色决定放行的表。
 *
 * 当前从 UserAuthService（内存用户）查角色；Phase 06 接入 JWT 后，
 * userId 来自 token，角色仍由这里统一解析。
 */
@Component
public class RoleResolver {

    private final UserAuthService userAuthService;

    public RoleResolver(UserAuthService userAuthService) {
        this.userAuthService = userAuthService;
    }

    public Role resolve(Long userId) {
        return userAuthService.roleOf(userId);
    }
}
