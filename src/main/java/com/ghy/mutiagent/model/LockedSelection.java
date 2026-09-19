package com.ghy.mutiagent.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 锁定集合（S07）：用户已确认的候选，与推荐池分开保存。
 *
 * - orderedKeys 有序去重（类型化 PlaceKey 字符串），保存用户选择来源与锁定时间；
 * - 重新生成推荐池不得清空锁定项（旧实现的 picked.retainAll(pool) 即此反例）；
 * - 锁定项与新约束冲突时：保留用户选择记录、标记冲突（LOCKED_CONSTRAINT_CONFLICT），
 *   并阻止直接提交，交给用户明确解锁或调整需求。
 */
@Data
public class LockedSelection {

    public static final String CONFLICT_LOCKED_CONSTRAINT = "LOCKED_CONSTRAINT_CONFLICT";

    /** 有序去重锁定键 */
    private List<String> orderedKeys = new ArrayList<>();
    /** key -> 选择来源说明（USER_LOCKED 等） */
    private Map<String, String> sources = new LinkedHashMap<>();
    private LocalDateTime lockedAt;
    /** 与新约束的冲突码（非空时禁止直接提交） */
    private List<String> conflictCodes = new ArrayList<>();

    public void lock(String key, String source) {
        if (!orderedKeys.contains(key)) {
            orderedKeys.add(key);
            sources.put(key, source == null ? "USER_LOCKED" : source);
        }
    }

    public boolean hasConflicts() {
        return conflictCodes != null && !conflictCodes.isEmpty();
    }
}
