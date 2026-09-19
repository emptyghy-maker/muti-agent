package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.ConstraintEntry;
import com.ghy.mutiagent.model.RequirementSnapshot;
import com.ghy.mutiagent.model.TravelState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 需求合并器（S02）：按 key/value 把每轮解析出的约束合并进快照。
 * - 新增则添加；完全相同则去重；同 key 不同 value 则旧条目标记 REVOKED（保留历史）后新增；
 * - 明确撤销目标（status=REVOKED）只撤销该 key，不影响其他独立约束；
 * - 每轮向所有 Agent 传递的是同一份 revision 的有效快照；
 * - extraRequest 只作为快照渲染的兼容摘要，不再被每轮覆盖。
 */
public final class RequirementMerger {

    public static final String ACTIVE = "ACTIVE";
    public static final String REVOKED = "REVOKED";

    private RequirementMerger() {
    }

    /** 把本轮解析结果并入状态快照；revision 只在有实际变化时递增 */
    public static void mergeInto(TravelState state, RuleParseResult parsed) {
        if (parsed == null) {
            return;
        }
        RequirementSnapshot snap = state.getRequirementSnapshot();
        if (snap == null) {
            snap = new RequirementSnapshot();
            state.setRequirementSnapshot(snap);
        }
        boolean changed = false;
        for (ConstraintEntry e : parsed.getConstraints() == null ? List.<ConstraintEntry>of() : parsed.getConstraints()) {
            changed |= applyEntry(snap, e);
        }
        if (parsed.getBudget() != null) {
            snap.setBudget(parsed.getBudget());
            changed = true;
        }
        if (parsed.getUnresolvedText() != null && !parsed.getUnresolvedText().isBlank()
                && !snap.getUnparsedTexts().contains(parsed.getUnresolvedText())) {
            snap.getUnparsedTexts().add(parsed.getUnresolvedText());
            changed = true;
        }
        if (changed) {
            snap.setRevision(snap.getRevision() + 1);
        }
    }

    private static boolean applyEntry(RequirementSnapshot snap, ConstraintEntry e) {
        if (e == null || e.getKey() == null) {
            return false;
        }
        List<ConstraintEntry> list = snap.getConstraints() == null ? new ArrayList<>() : snap.getConstraints();
        snap.setConstraints(list);
        if (REVOKED.equals(e.getStatus())) {
            boolean changed = false;
            for (ConstraintEntry old : list) {
                if (old.getKey().equals(e.getKey()) && ACTIVE.equals(old.getStatus())) {
                    old.setStatus(REVOKED);
                    changed = true;
                }
            }
            return changed;
        }
        for (ConstraintEntry old : list) {
            if (old.getKey().equals(e.getKey()) && ACTIVE.equals(old.getStatus())) {
                if (java.util.Objects.equals(old.getValue(), e.getValue())) {
                    return false; // 完全相同，去重
                }
                old.setStatus(REVOKED); // 单值字段更改：旧值保留历史，新值生效
            }
        }
        if (e.getId() == null) {
            e.setId("c-" + String.format("%03d", list.size() + 1));
        }
        if (e.getStatus() == null) {
            e.setStatus(ACTIVE);
        }
        if (e.getSource() == null) {
            e.setSource("USER");
        }
        list.add(e);
        return true;
    }

    /** key 是否有 ACTIVE 约束 */
    public static boolean isActive(RequirementSnapshot snap, String key) {
        if (snap == null || snap.getConstraints() == null) {
            return false;
        }
        return snap.getConstraints().stream()
                .anyMatch(c -> c.getKey().equals(key) && ACTIVE.equals(c.getStatus()));
    }

    /** 有效约束的兼容摘要（extraRequest 渲染）：按原文去重，含已确认的特殊需求与未解析残余 */
    public static String renderExtra(TravelState state) {
        Set<String> parts = new LinkedHashSet<>();
        RequirementSnapshot snap = state.getRequirementSnapshot();
        if (snap != null && snap.getConstraints() != null) {
            for (ConstraintEntry c : snap.getConstraints()) {
                if (ACTIVE.equals(c.getStatus()) && c.getOriginalText() != null
                        && !c.getOriginalText().isBlank()) {
                    parts.add(c.getOriginalText());
                }
            }
        }
        if (state.getPreference() != null && state.getPreference().getSpecialRequests() != null
                && !state.getPreference().getSpecialRequests().isBlank()) {
            for (String piece : state.getPreference().getSpecialRequests().split("[；;]")) {
                if (!piece.isBlank()) {
                    parts.add(piece.trim());
                }
            }
        }
        // 未解析残余同样必须可见（规则没识别不等于没有需求）：让 Agent 至少看到原文
        if (snap != null && snap.getUnparsedTexts() != null) {
            for (String t : snap.getUnparsedTexts()) {
                if (t != null && !t.isBlank()) {
                    parts.add(t);
                }
            }
        }
        return String.join("；", parts);
    }
}
