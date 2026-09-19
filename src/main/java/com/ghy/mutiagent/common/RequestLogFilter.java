package com.ghy.mutiagent.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 全局请求日志过滤器：让每个 HTTP 请求都在控制台留痕，便于后台监控。
 *
 * 进入时打一行（方法 + URI + 来源 IP），完成时打一行（状态码 + 耗时）。
 * 作为 Servlet Filter 注册（@Component），先于 Spring Security 过滤链执行，
 * 因此登录、鉴权失败、业务接口等所有请求都会被记录。
 *
 * 注意：SSE 接口（/api/v1/chat/stream）的「完成」行在连接建立时就会打出，
 * 因为控制器一返回 SseEmitter，servlet 过滤链即返回；真正的流式内容由
 * 业务编排逻辑里的 log 继续输出。
 */
@Component
@Order(1)
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.currentTimeMillis();
        String method = request.getMethod();
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        String ip = clientIp(request);
        String fullUri = query == null ? uri : uri + "?" + query;

        log.info("[HTTP] >>> {} {} (IP={})", method, fullUri, ip);
        try {
            chain.doFilter(request, response);
        } finally {
            int status = response.getStatus();
            long cost = System.currentTimeMillis() - start;
            // 4xx/5xx 用 WARN 级别，控制台一眼可见被拒绝/失败的请求
            if (status >= 400) {
                log.warn("[HTTP] <<< {} {} -> status={} 耗时={}ms", method, fullUri, status, cost);
            } else {
                log.info("[HTTP] <<< {} {} -> status={} 耗时={}ms", method, fullUri, status, cost);
            }
        }
    }

    private String clientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) {
            return xf.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
