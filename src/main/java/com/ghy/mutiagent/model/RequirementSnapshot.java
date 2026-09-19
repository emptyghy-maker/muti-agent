package com.ghy.mutiagent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 需求快照（S02）：跨轮累积的有效约束，是各 Agent 每一轮都拿到的同一份需求版本。
 * - revision 是需求版本（与步骤七的会话 revision 区分命名）；
 * - constraints 只保存有效约束与撤销记录，不保存无限聊天历史；
 * - unparsedTexts 保存模型不可用时无法解析的原文（LEGACY_UNPARSED 迁移入口），不得丢弃或宣称已落实。
 */
@Data
public class RequirementSnapshot {
    private int schemaVersion = 2;
    private int revision;
    private List<ConstraintEntry> constraints = new ArrayList<>();
    private BudgetSpec budget;
    private List<String> unparsedTexts = new ArrayList<>();
}
