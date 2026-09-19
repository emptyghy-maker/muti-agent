package com.ghy.mutiagent.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 需求分析结论（RequirementAgent 输出）：
 * 判断本次规划走标准流程（workflow）还是需要 Agent 深度分析（agent），
 * 并给出关键关注点（如：情侣氛围/夜景/预算敏感），供后续景点/美食/酒店各环节围绕执行。
 */
@Data
public class RequirementAnalysis {
    /** workflow = 标准流程（规则快出）；agent = 有特殊需求，各环节交给 Agent 分析 */
    private String mode;
    /** 关键关注点，如 ["情侣氛围","夜景","人均不高"] */
    private List<String> focus;
    /** 一句话分析简报，展示给用户 */
    private String brief;
    /** 各准则需求强度 1~5（path/cost/sightseeing/food），用于 AHP 评分权重的灵活调整 */
    private Map<String, Integer> needs;
    /** 需求关键字（用于候选匹配，如 citywalk→街区/步行、情侣→夜景/游船、省钱→免费/低价） */
    private List<String> tags;
}
