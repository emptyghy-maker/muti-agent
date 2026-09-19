package com.ghy.mutiagent.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** S07 锁定集合：有序去重、来源记录、冲突标记（池重建不清空锁定） */
class LockedSelectionTest {

    @Test
    void lockDeduplicatesAndKeepsOrder() {
        LockedSelection l = new LockedSelection();
        l.lock("ATTRACTION:10", "USER_LOCKED");
        l.lock("ATTRACTION:10", "USER_LOCKED");
        l.lock("ATTRACTION:20", "USER_LOCKED");
        assertThat(l.getOrderedKeys()).containsExactly("ATTRACTION:10", "ATTRACTION:20");
        assertThat(l.getSources().get("ATTRACTION:10")).isEqualTo("USER_LOCKED");
    }

    @Test
    void typedKeysDoNotCollideAcrossTypes() {
        LockedSelection l = new LockedSelection();
        l.lock("ATTRACTION:1", "USER_LOCKED");
        l.lock("FOOD:1", "USER_LOCKED");
        assertThat(l.getOrderedKeys()).containsExactly("ATTRACTION:1", "FOOD:1");
    }

    @Test
    void conflictsFlaggedWhenConstraintConflicts() {
        LockedSelection l = new LockedSelection();
        l.lock("ATTRACTION:10", "USER_LOCKED");
        assertThat(l.hasConflicts()).isFalse();
        l.getConflictCodes().add(LockedSelection.CONFLICT_LOCKED_CONSTRAINT);
        assertThat(l.hasConflicts()).isTrue();
    }
}
