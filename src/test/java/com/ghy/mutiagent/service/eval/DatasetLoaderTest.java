package com.ghy.mutiagent.service.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 数据集加载器：内容哈希、样本顺序、结构错误与 fixture 可执行性诊断 */
class DatasetLoaderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private String seedRaw() throws Exception {
        Path p = Path.of("src/test/resources/eval/dataset-seed-mini.json");
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void loadKeepsSampleOrderAndComputesHash() throws Exception {
        String raw = seedRaw();
        DatasetFile file = DatasetLoader.parse(raw, DatasetLoader.sha256(raw.getBytes(StandardCharsets.UTF_8)));

        assertEquals(List.of("ordinary", "diet", "injection"),
                file.samples().stream().map(DatasetSample::id).toList());
        assertTrue(file.schemaErrors().isEmpty());
        assertTrue(file.fixtureValidationErrors().isEmpty());
        assertEquals("travel-p2-test-mini", file.datasetId());
        assertEquals("hangzhou-synthetic-v1", file.toolSnapshot().id());
        assertEquals(List.of("ATTRACTION:1", "ATTRACTION:2", "FOOD:1", "FOOD:2", "HOTEL:1"),
                file.toolSnapshot().placeKeys());
        assertFalse(file.datasetHash().isBlank());
        assertEquals(64, file.datasetHash().length());
    }

    @Test
    void hashIsStableForSameBytes() {
        byte[] bytes = "{\"samples\":[]}".getBytes(StandardCharsets.UTF_8);
        assertEquals(DatasetLoader.sha256(bytes), DatasetLoader.sha256(bytes));
    }

    @Test
    void invalidJsonReportsSchemaError() {
        DatasetFile file = DatasetLoader.parse("{not json", "h");
        assertEquals(List.of(DatasetLoader.INVALID_JSON), file.schemaErrors());
        assertTrue(file.samples().isEmpty());
    }

    @Test
    void missingSamplesArrayReportsSchemaError() {
        DatasetFile file = DatasetLoader.parse("{\"datasetId\":\"x\"}", "h");
        assertTrue(file.schemaErrors().contains(DatasetLoader.MISSING_SAMPLES));
    }

    @Test
    void missingToolSnapshotReportsSchemaError() throws Exception {
        JsonNode root = JSON.readTree(seedRaw());
        ((ObjectNode) root).remove("toolSnapshot");
        DatasetFile file = DatasetLoader.parse(root.toString(), "h");
        assertTrue(file.schemaErrors().contains(DatasetLoader.MISSING_TOOL_SNAPSHOT));
    }

    @Test
    void unknownFixtureVersionReportsFixtureError() throws Exception {
        JsonNode root = JSON.readTree(seedRaw());
        ((ObjectNode) root.get("samples").get(0)).put("fixtureVersion", "no-such-fixture");
        DatasetFile file = DatasetLoader.parse(root.toString(), "h");
        assertFalse(file.fixtureValidationErrors().isEmpty());
        assertTrue(file.fixtureValidationErrors().get(0).contains("no-such-fixture"));
    }
}
