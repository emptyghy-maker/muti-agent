package com.ghy.mutiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.common.Result;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.security.JwtAuthFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final JwtAuthFilter jwtAuthFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, ObjectMapper objectMapper) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.objectMapper = objectMapper;
    }

    /**
     * 无状态 JWT 鉴权：
     * - 放行登录接口和健康检查，其余请求都要认证；
     * - JWT 过滤器在用户名密码过滤器之前执行；
     * - 未认证/无权限走自定义出口：控制台 WARN 留痕 + JSON 错误体（401/403），
     *   避免默认行为下请求被静默拒绝（之前 403 时控制台无任何日志）。
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 放行异步 dispatch（SSE 流结束后会触发）和错误页 dispatch（/error），
                // 否则这些内部转发会被鉴权拦下，刷出一堆 AccessDeniedException
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/v1/auth/login", "/api/v1/health").permitAll()
                // 用量审计管理台 + 调用链路查询：仅 ADMIN 角色（JwtAuthFilter 已按 role 声明 ROLE_xxx 权限）
                .requestMatchers("/api/v1/usage/admin/**", "/api/v1/trace/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authEntryPoint())
                .accessDeniedHandler(accessDeniedHandler()))
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 未认证（缺少/无效 token）统一出口：留痕 + JSON 401 */
    private AuthenticationEntryPoint authEntryPoint() {
        return (request, response, e) -> {
            log.warn("[SECURITY] 拒绝未认证请求: {} {} (IP={}) 原因: {}",
                    request.getMethod(), request.getRequestURI(), clientIp(request),
                    e == null ? "未携带有效 token" : e.getMessage());
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, ResultCode.UNAUTHORIZED);
        };
    }

    /** 已认证但角色不足统一出口：留痕 + JSON 403 */
    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, e) -> {
            log.warn("[SECURITY] 拒绝无权限请求: {} {} (IP={}) 原因: {}",
                    request.getMethod(), request.getRequestURI(), clientIp(request),
                    e == null ? "权限不足" : e.getMessage());
            writeJson(response, HttpServletResponse.SC_FORBIDDEN, ResultCode.FORBIDDEN);
        };
    }

    private void writeJson(HttpServletResponse response, int status, ResultCode rc) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), Result.fail(rc));
    }

    private String clientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) {
            return xf.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
