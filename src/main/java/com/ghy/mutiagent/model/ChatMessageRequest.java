package com.ghy.mutiagent.model;

import lombok.Data;

/** 规划对话请求（同步接口） */
@Data
public class ChatMessageRequest {
    private String sessionId;
    private String message;
}
