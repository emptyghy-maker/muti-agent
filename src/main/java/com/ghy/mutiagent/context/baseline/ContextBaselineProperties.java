package com.ghy.mutiagent.context.baseline;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 短期上下文基线观测配置；默认关闭，关闭时业务链路只走 No-op。 */
@Component
@ConfigurationProperties(prefix = "context.baseline")
public class ContextBaselineProperties {

    private boolean enabled;
    private boolean logEnabled = true;
    private boolean hashEnabled = true;
    private String estimateMethod = "MIXED_TEXT_HEURISTIC_V1";
    private int maxKeyCount = 64;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isLogEnabled() {
        return logEnabled;
    }

    public void setLogEnabled(boolean logEnabled) {
        this.logEnabled = logEnabled;
    }

    public boolean isHashEnabled() {
        return hashEnabled;
    }

    public void setHashEnabled(boolean hashEnabled) {
        this.hashEnabled = hashEnabled;
    }

    public String getEstimateMethod() {
        return estimateMethod;
    }

    public void setEstimateMethod(String estimateMethod) {
        this.estimateMethod = estimateMethod;
    }

    public int getMaxKeyCount() {
        return maxKeyCount;
    }

    public void setMaxKeyCount(int maxKeyCount) {
        this.maxKeyCount = maxKeyCount;
    }
}
