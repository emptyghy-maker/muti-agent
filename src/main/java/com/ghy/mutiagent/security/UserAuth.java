package com.ghy.mutiagent.security;

import com.ghy.mutiagent.model.enums.Role;

/**
 * 认证用户（内存版）。生产环境应换成数据库 users 表 + BCrypt 加密密码。
 */
public record UserAuth(Long id, String username, String password, Role role) {
}
