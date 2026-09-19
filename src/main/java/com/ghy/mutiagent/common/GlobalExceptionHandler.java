package com.ghy.mutiagent.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：401/403/404/409 映射为对应 HTTP 状态，其余保持 200 + 业务码（前端按 code 处理） */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e) {
        HttpStatus status = HttpStatus.OK;
        int code = e.getCode();
        if (code == ResultCode.UNAUTHORIZED.getCode()) {
            status = HttpStatus.UNAUTHORIZED;
        } else if (code == ResultCode.FORBIDDEN.getCode()) {
            status = HttpStatus.FORBIDDEN;
        } else if (code == ResultCode.RESOURCE_NOT_FOUND.getCode()) {
            status = HttpStatus.NOT_FOUND;
        } else if (code == ResultCode.STATE_CONFLICT.getCode()
                || code == ResultCode.IDEMPOTENCY_CONFLICT.getCode()
                || code == ResultCode.SESSION_EXPIRED.getCode()) {
            status = HttpStatus.CONFLICT;
        } else if (code == ResultCode.PLAN_INVALID.getCode()) {
            // 422：结构/发布校验违规，响应体携带具体 violations（S06-B 协议）
            status = HttpStatus.UNPROCESSABLE_ENTITY;
        } else if (code == ResultCode.READ_ONLY.getCode()) {
            status = HttpStatus.LOCKED;
        } else if (code == ResultCode.STORAGE_UNAVAILABLE.getCode()) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
        }
        return ResponseEntity.status(status).body(Result.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        return Result.fail(ResultCode.PARAM_ERROR);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("系统异常", e);
        return Result.fail(ResultCode.SYSTEM_ERROR);
    }
}
