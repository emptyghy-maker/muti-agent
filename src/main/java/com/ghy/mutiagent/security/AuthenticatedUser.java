package com.ghy.mutiagent.security;

/**
 * 已认证用户主体，由 JwtAuthFilter 从 token 解析后放入 SecurityContext。
 */
public record AuthenticatedUser(Long id, String username, String role) {
}
