package com.ghy.mutiagent.model;

import lombok.Data;

/** 会话漏斗步骤：到达该环节的会话数 */
@Data
public class UsageFunnelStep {
    private String label;
    private int sessions;
}
