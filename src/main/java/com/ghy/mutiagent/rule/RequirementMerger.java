package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.ConstraintEntry;
import com.ghy.mutiagent.model.RequirementSnapshot;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.model.requirement.InterpretationStatus;
import com.ghy.mutiagent.model.requirement.RequirementScope;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
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
    public static final String SUPERSEDED = "SUPERSEDED";

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
            if (e != null && e.getSourceTurnId() == null) {
                e.setSourceTurnId("session:" + state.getSessionId() + ":snapshot:" + (snap.getRevision() + 1));
            }
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
            // 旧策略/旧验收报告绑定的是旧快照版本，需求一变立即失效。
            state.setResolvedPlanningPolicy(null);
            if (state.getRequirementFulfillmentReport() != null) {
                state.getRequirementFulfillmentReport().markStale();
            }
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
                if (semanticEquals(old, e)) {
                    return false; // 完全相同，去重
                }
                old.setStatus(SUPERSEDED); // 新版本替代旧版本，历史仍可追踪
                e.setSupersedesId(old.getId());
                e.setRevision(Math.max(1, old.getRevision() + 1));
            }
        }
        if (e.getId() == null) {
            e.setId("REQ-" + String.format("%04d", list.size() + 1));
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

    private static boolean semanticEquals(ConstraintEntry left, ConstraintEntry right) {
        return Objects.equals(left.getKey(), right.getKey())
                && Objects.equals(left.getValue(), right.getValue())
                && Objects.equals(left.getSubject(), right.getSubject())
                && Objects.equals(left.getCount(), right.getCount())
                && Objects.equals(left.getOperator(), right.getOperator())
                && Objects.equals(left.getUnit(), right.getUnit())
                && Objects.equals(left.getScope(), right.getScope())
                && Objects.equals(left.getInterpretationStatus(), right.getInterpretationStatus());
    }

    /**
     * 把单独一轮的范围回答（例如“全程”或“每天”）应用到最近一条待澄清餐次需求。
     * 返回新版本需求；若消息不是纯范围回答或没有待澄清需求则返回 null。
     */
    public static ConstraintEntry resolvePendingMealScope(TravelState state, String message) {
        if (state == null || message == null) {
            return null;
        }
        String normalized = message.replaceAll("[，,。.、；;！!？?\\s]", "");
        RequirementScope scope;
        if (normalized.matches("全程|整个行程|整个旅程|这趟旅行|这趟行程|一共|总共")) {
            scope = RequirementScope.TRIP;
        } else if (normalized.matches("每天|每日|每一天|天天")) {
            scope = RequirementScope.PER_DAY;
        } else {
            return null;
        }
        RequirementSnapshot snapshot = state.getRequirementSnapshot();
        if (snapshot == null || snapshot.getConstraints() == null) {
            return null;
        }
        ConstraintEntry pending = null;
        for (ConstraintEntry entry : snapshot.getConstraints()) {
            if (ACTIVE.equals(entry.getStatus())
                    && (entry.getInterpretationStatus() == InterpretationStatus.NEEDS_CLARIFICATION
                    || entry.getInterpretationStatus() == InterpretationStatus.LEGACY_UNRESOLVED)) {
                pending = entry;
            }
        }
        if (pending == null) {
            return null;
        }
        String pendingTurn = pending.getSourceTurnId();
        RuleParseResult parsed = new RuleParseResult();
        ConstraintEntry lastResolved = null;
        for (ConstraintEntry entry : snapshot.getConstraints()) {
            if (!ACTIVE.equals(entry.getStatus())
                    || (entry.getInterpretationStatus() != InterpretationStatus.NEEDS_CLARIFICATION
                    && entry.getInterpretationStatus() != InterpretationStatus.LEGACY_UNRESOLVED)
                    || !Objects.equals(pendingTurn, entry.getSourceTurnId())) {
                continue;
            }
            ConstraintEntry resolved = copyOf(entry);
            resolved.setId(null);
            resolved.setScope(scope);
            resolved.setInterpretationStatus(InterpretationStatus.CONFIRMED);
            resolved.setReasonCode(null);
            resolved.setSourceTurnId(null);
            resolved.setOriginalText(entry.getOriginalText() + "（范围确认：" + normalized + "）");
            parsed.getConstraints().add(resolved);
            lastResolved = resolved;
        }
        mergeInto(state, parsed);
        return lastResolved;
    }

    private static ConstraintEntry copyOf(ConstraintEntry source) {
        ConstraintEntry target = new ConstraintEntry();
        target.setKey(source.getKey());
        target.setValue(source.getValue());
        target.setHardness(source.getHardness());
        target.setStatus(source.getStatus());
        target.setSource(source.getSource());
        target.setOriginalText(source.getOriginalText());
        target.setSubject(source.getSubject());
        target.setCount(source.getCount());
        target.setOperator(source.getOperator());
        target.setUnit(source.getUnit());
        target.setScope(source.getScope());
        target.setInterpretationStatus(source.getInterpretationStatus());
        target.setRevision(source.getRevision());
        return target;
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
