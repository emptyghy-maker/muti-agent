package com.ghy.mutiagent.common;

/**
 * 统一响应结构：所有 REST 接口都返回这个对象。
 * 格式：{"code":0,"message":"ok","data":{...}}
 *
 * - code = 0 表示成功，非 0 表示业务错误（具体见 {@link ResultCode}）
 * - data 为真正的业务数据
 */
public class Result<T> {
    private int code;
    private String message;
    private T data;

    /** 成功返回，带数据 */
    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = ResultCode.SUCCESS.getCode();
        r.message = ResultCode.SUCCESS.getMessage();
        r.data = data;
        return r;
    }

    /** 成功返回，无数据 */
    public static <T> Result<T> ok() {
        return ok(null);
    }

    /** 失败返回（按错误码） */
    public static <T> Result<T> fail(ResultCode rc) {
        return fail(rc.getCode(), rc.getMessage());
    }

    /** 失败返回（自定义错误码和消息） */
    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
}
