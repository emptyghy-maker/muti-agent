package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.BudgetSpec;
import com.ghy.mutiagent.model.ConstraintEntry;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则解析结果（S02）：比旧 parse 多出快照约束、未解析残余与意图。
 * - updates：兼容旧字段更新（days/totalBudget/peopleCount/…，值 UNSURE 表示没想好）；
 * - constraints：快照条目（含明确撤销条目，status=REVOKED）；
 * - unresolvedText：确定性规则未能消费的残余原文（不能当作没有需求）；
 * - intents：PAGING（口语翻页）/ CONDITION（附带条件），混合意图二者并存。
 */
@Data
public class RuleParseResult {
    private Map<String, String> updates = new LinkedHashMap<>();
    private List<ConstraintEntry> constraints = new ArrayList<>();
    private BudgetSpec budget;
    private String unresolvedText;
    private List<String> intents = new ArrayList<>();
}
