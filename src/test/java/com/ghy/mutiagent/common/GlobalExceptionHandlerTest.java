package com.ghy.mutiagent.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-S01 契约测试：BizException 错误码到 HTTP 状态的映射。
 * 前端依赖该映射区分「未登录/无权限/资源不可见/状态冲突」与普通业务失败。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private ResponseEntity<Result<Void>> handle(ResultCode rc) {
        return handler.handleBiz(new BizException(rc));
    }

    @Test
    void shouldMapUnauthorizedTo401() {
        ResponseEntity<Result<Void>> r = handle(ResultCode.UNAUTHORIZED);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().getCode()).isEqualTo(ResultCode.UNAUTHORIZED.getCode());
    }

    @Test
    void shouldMapForbiddenTo403() {
        ResponseEntity<Result<Void>> r = handle(ResultCode.FORBIDDEN);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody().getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode());
    }

    @Test
    void shouldMapResourceNotFoundTo404() {
        ResponseEntity<Result<Void>> r = handle(ResultCode.RESOURCE_NOT_FOUND);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody().getCode()).isEqualTo(ResultCode.RESOURCE_NOT_FOUND.getCode());
    }

    @Test
    void shouldMapStateConflictTo409() {
        ResponseEntity<Result<Void>> r = handle(ResultCode.STATE_CONFLICT);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().getCode()).isEqualTo(ResultCode.STATE_CONFLICT.getCode());
    }

    @Test
    void shouldKeepOtherCodesAs200WithBusinessCode() {
        ResponseEntity<Result<Void>> r = handle(ResultCode.SYSTEM_ERROR);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().getCode()).isEqualTo(ResultCode.SYSTEM_ERROR.getCode());
    }
}
