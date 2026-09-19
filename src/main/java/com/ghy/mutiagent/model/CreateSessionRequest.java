package com.ghy.mutiagent.model;

import lombok.Data;

/** 创建规划会话请求：选择目的地 */
@Data
public class CreateSessionRequest {
    private Long destinationId;
}
