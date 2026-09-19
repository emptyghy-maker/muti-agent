package com.ghy.mutiagent.model;

import lombok.Data;

/** 会话聊天历史条目（恢复窗口时从用量审计流水回放） */
@Data
public class HistoryItem {
    /** user / assistant */
    private String role;
    private String text;
}
