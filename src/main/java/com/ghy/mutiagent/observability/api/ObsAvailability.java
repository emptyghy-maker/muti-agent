package com.ghy.mutiagent.observability.api;

import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.observability.config.ObservabilityProperties;
import com.ghy.mutiagent.security.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 模块可用性与身份解析：
 * - enabled=false 时所有 API 返回明确「模块不可用」语义（HTTP 200 + 业务码，沿用老接口约定），
 *   不转向旧接口伪造完整数据；
 * - 每个入口显式解析 AuthenticatedUser，不依赖前端判断角色。
 */
@Component
public class ObsAvailability {

    private final ObservabilityProperties props;

    public ObsAvailability(ObservabilityProperties props) {
        this.props = props;
    }

    public void requireEnabled() {
        if (!props.isEnabled()) {
            throw new BizException(ResultCode.EXECUTE_ERROR.getCode(),
                    "Observability 模块未启用（observability.enabled=false）");
        }
    }

    public AuthenticatedUser requireActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }
        throw new BizException(ResultCode.UNAUTHORIZED);
    }

    public boolean enabled() {
        return props.isEnabled();
    }
}
