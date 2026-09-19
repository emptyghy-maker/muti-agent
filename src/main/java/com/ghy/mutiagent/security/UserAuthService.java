package com.ghy.mutiagent.security;

import com.ghy.mutiagent.model.enums.Role;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 认证用户存储（内存版）。
 *
 * 演示用：内置两个账号。生产环境应换成数据库查询 + 密码 BCrypt 校验。
 * - admin / admin123  → ADMIN（管理员）
 * - analyst / analyst123 → ANALYST（普通用户）
 */
@Service
public class UserAuthService {

    private static final List<UserAuth> USERS = List.of(
            new UserAuth(1L, "admin", "admin123", Role.ADMIN),
            new UserAuth(2L, "analyst", "analyst123", Role.ANALYST)
    );

    /** 用户名密码校验，成功返回用户，失败返回空 */
    public Optional<UserAuth> authenticate(String username, String password) {
        return USERS.stream()
                .filter(u -> u.username().equals(username) && u.password().equals(password))
                .findFirst();
    }

    /** 按 userId 取角色，未知用户默认 ANALYST（更严格） */
    public Role roleOf(Long userId) {
        return USERS.stream()
                .filter(u -> u.id().equals(userId))
                .map(UserAuth::role)
                .findFirst()
                .orElse(Role.ANALYST);
    }
}
