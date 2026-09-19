package com.ghy.mutiagent.service.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 版本管理：隔离环境切换/回退、operation 启动绑定不受切换影响、审计记录 */
class PromptVersionManagerTest {

    @Test
    void activateRollbackBindsNewOperationsButNotInflight() {
        PromptVersionManager manager = new PromptVersionManager("baseline-v1");
        String inflight = manager.bind("op-inflight");

        manager.activate("candidate-v2", true);
        String beforeRollback = manager.bind("op-new-1");

        manager.rollback();
        String afterRollback = manager.bind("op-new-2");

        assertEquals("baseline-v1", inflight);
        assertEquals("baseline-v1", manager.boundVersion("op-inflight"));
        assertEquals("candidate-v2", beforeRollback);
        assertEquals("baseline-v1", afterRollback);
        assertEquals(List.of("ACTIVATE", "ROLLBACK"), manager.auditActions());
        assertEquals("baseline-v1", manager.activeVersion());
    }

    @Test
    void activateRefusesProductionEnvironment() {
        PromptVersionManager manager = new PromptVersionManager("baseline-v1");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> manager.activate("candidate-v2", false));
        assertTrue(e.getMessage().contains("隔离环境"));
        assertEquals("baseline-v1", manager.activeVersion());
        assertTrue(manager.auditActions().isEmpty());
    }

    @Test
    void rollbackWithoutHistoryRefused() {
        PromptVersionManager manager = new PromptVersionManager("baseline-v1");
        assertThrows(IllegalStateException.class, manager::rollback);
    }

    @Test
    void activatingSameVersionKeepsAuditClean() {
        PromptVersionManager manager = new PromptVersionManager("baseline-v1");
        manager.activate("baseline-v1", true);
        assertTrue(manager.auditActions().isEmpty());
        assertEquals("baseline-v1", manager.activeVersion());
    }

    @Test
    void multipleActivationsRollBackInOrder() {
        PromptVersionManager manager = new PromptVersionManager("v0");
        manager.activate("v1", true);
        manager.activate("v2", true);
        assertEquals("v2", manager.activeVersion());
        manager.rollback();
        assertEquals("v1", manager.activeVersion());
        manager.rollback();
        assertEquals("v0", manager.activeVersion());
        assertEquals(List.of("ACTIVATE", "ACTIVATE", "ROLLBACK", "ROLLBACK"),
                manager.auditActions());
    }

    @Test
    void auditEntriesCarryVersions() {
        PromptVersionManager manager = new PromptVersionManager("baseline-v1");
        manager.activate("candidate-v2", true);
        manager.rollback();
        assertEquals(2, manager.auditEntries().size());
        assertEquals("ACTIVATE", manager.auditEntries().get(0).get("action"));
        assertEquals("candidate-v2", manager.auditEntries().get(0).get("version"));
        assertEquals("ROLLBACK", manager.auditEntries().get(1).get("action"));
    }
}
