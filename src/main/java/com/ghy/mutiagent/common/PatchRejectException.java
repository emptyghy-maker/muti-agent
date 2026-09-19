package com.ghy.mutiagent.common;

/**
 * S09 补丁拒绝：范围外/候选证据不足/全局校验失败/版本冲突等，以明确错误码终止调整。
 * 错误码是机器可读码（如 PATCH_OUT_OF_SCOPE / GLOBAL_BUDGET_EXCEEDED / REVISION_CONFLICT），
 * 由操作协议原样写入 error_code，失败不覆盖旧版本。
 */
public class PatchRejectException extends BizException {

    private final String errorCode;

    public PatchRejectException(String errorCode, String message) {
        super(ResultCode.PLAN_INVALID);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
