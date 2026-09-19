package com.ghy.mutiagent.common;

/**
 * 统一错误码表。
 * 约定：0 成功；4xxxx 客户端问题（参数/鉴权/权限）；5xxxx 服务端问题（执行/LLM/系统）。
 */
public enum ResultCode {
    SUCCESS(0, "ok"),
    PARAM_ERROR(40001, "参数错误"),
    UNAUTHORIZED(40100, "未授权"),
    FORBIDDEN(40300, "无权限"),
    RESOURCE_NOT_FOUND(40400, "资源不存在或不属于当前用户"),
    STATE_CONFLICT(40900, "资源状态冲突"),
    IDEMPOTENCY_CONFLICT(40901, "同一 requestId 的请求体与已记录操作不一致"),
    SESSION_EXPIRED(40902, "会话已过期，不允许继续写入"),
    READ_ONLY(42300, "只读回退中，写操作已关闭"),
    EXECUTE_ERROR(50001, "执行失败"),
    LLM_ERROR(50002, "LLM 调用失败"),
    SESSION_NOT_FOUND(50003, "会话不存在或已过期"),
    PLAN_INVALID(50004, "Agent 输出未通过结构验证，拒绝发布"),
    DEADLINE_EXCEEDED(50005, "操作超出截止时间，已安全中止"),
    OPERATION_CANCELLED(40904, "操作已取消"),
    SYSTEM_ERROR(50000, "系统错误"),
    STORAGE_UNAVAILABLE(50301, "权威存储不可用");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
}
