package com.ghy.mutiagent.service.eval;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 判据快照：深拷贝不可变，外部修改源 map 不影响快照，执行前后导出稳定一致 */
class JudgeConfigTest {

    @Test
    void constructorDeepCopiesInputMaps() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("budget", 1000);
        List<String> forbidden = new ArrayList<>(List.of("ATTRACTION:2"));
        expected.put("forbiddenPlaceKeys", forbidden);

        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("id", "fixture-1");

        JudgeConfig config = new JudgeConfig("d1", expected, tools, "synthetic", "judge-v1");

        expected.put("budget", 999);
        forbidden.add("HOTEL:1");
        tools.put("id", "mutated");

        assertEquals(1000, config.expected().get("budget"));
        assertEquals(List.of("ATTRACTION:2"), config.expected().get("forbiddenPlaceKeys"));
        assertEquals("fixture-1", config.toolSnapshot().get("id"));
    }

    @Test
    void returnedMapsAreIndependentCopies() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("days", 2);
        JudgeConfig config = new JudgeConfig("d1", expected,
                Map.of("id", "fixture-1"), "synthetic", "judge-v1");

        config.expected().put("days", 9);
        config.toolSnapshot().put("id", "hacked");

        assertEquals(2, config.expected().get("days"));
        assertEquals("fixture-1", config.toolSnapshot().get("id"));
    }

    @Test
    void snapshotIsDeepIndependentCopy() {
        JudgeConfig config = new JudgeConfig("d1", Map.of("a", "b"), Map.of("k", "v"),
                "synthetic", "judge-v1");
        JudgeConfig copy = config.snapshot();
        assertNotSame(config, copy);
        assertEquals(config.toMap(), copy.toMap());

        copy.expected().put("a", "changed");
        assertEquals("b", config.expected().get("a"));
    }

    @Test
    void toMapIsStableAcrossCalls() {
        JudgeConfig config = new JudgeConfig("d1", Map.of("budget", 300), Map.of("id", "f1"),
                "synthetic", "judge-v1");
        assertEquals(config.toMap(), config.toMap());
    }
}
