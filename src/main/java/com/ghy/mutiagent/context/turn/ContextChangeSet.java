package com.ghy.mutiagent.context.turn;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 解释结果通过业务检查后形成的变更说明；第二阶段先用于影子比较和审计。 */
@Data
public class ContextChangeSet {
    private Map<String, Object> oldValues = new LinkedHashMap<>();
    private Map<String, Object> newValues = new LinkedHashMap<>();
    private List<String> addedRequirementIds = new ArrayList<>();
    private List<String> supersededRequirementIds = new ArrayList<>();
    private List<String> revokedRequirementIds = new ArrayList<>();
    private Set<String> affectedChannels = new LinkedHashSet<>();
    private boolean requirementRevisionChanged;
    private boolean constraintRevisionChanged;
    private boolean invalidatePlan;
    private boolean needsClarification;
}
