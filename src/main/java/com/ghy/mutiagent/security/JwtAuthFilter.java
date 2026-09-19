package com.ghy.mutiagent.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器：从请求里取出 token，校验通过后把用户信息塞进 SecurityContext。
 *
 * token 两个来源：
 * 1. 请求头 Authorization: Bearer <token>（普通 REST 请求）；
 * 2. query 参数 ?token=<token>（EventSource/SSE 无法带自定义头，只能走这里）。
 *
 * 校验失败时不在这里直接报错，而是清空上下文继续放行，交给 SecurityConfig 的鉴权规则返回 401/403。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtUtil jwtUtil;

    public JwtAuthFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token == null) {
            log.debug("[SECURITY] 请求未携带 token: {} {}", request.getMethod(), request.getRequestURI());
        } else if (SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims claims = jwtUtil.parse(token);
                Long uid = claims.get("uid", Long.class);
                String username = claims.getSubject();
                String role = claims.get("role", String.class);

                AuthenticatedUser principal = new AuthenticatedUser(uid, username, role);
                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
                var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.debug("[SECURITY] token 校验通过: user={} role={}", username, role);
            } catch (JwtException | IllegalArgumentException e) {
                // token 无效/过期：留痕后清空上下文，继续走后续鉴权（会因未认证被拒）
                log.warn("[SECURITY] token 校验失败: {} {} 原因: {}",
                        request.getMethod(), request.getRequestURI(), e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return request.getParameter("token");
    }
}
