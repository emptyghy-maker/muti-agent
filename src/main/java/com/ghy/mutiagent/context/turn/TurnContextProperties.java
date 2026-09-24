package com.ghy.mutiagent.context.turn;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 单轮上下文开关：先影子比较，再按开关让新解析结果补充旧解析缺口。 */
@Component
@ConfigurationProperties(prefix = "context.turn")
public class TurnContextProperties {

    private boolean enabled;
    private boolean shadowEnabled = true;
    private boolean useNewParser;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isShadowEnabled() {
        return shadowEnabled;
    }

    public void setShadowEnabled(boolean shadowEnabled) {
        this.shadowEnabled = shadowEnabled;
    }

    public boolean isUseNewParser() {
        return useNewParser;
    }

    public void setUseNewParser(boolean useNewParser) {
        this.useNewParser = useNewParser;
    }
}
