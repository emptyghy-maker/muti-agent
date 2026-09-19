package com.ghy.mutiagent.service.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 契约验证器：四类损坏样本各自报出对应错误码，干净集无错误 */
class DatasetValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private DatasetFile load(String raw) {
        return DatasetLoader.parse(raw, DatasetLoader.sha256(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private ObjectNode seedRoot() throws Exception {
        Path p = Path.of("src/test/resources/eval/dataset-seed-mini.json");
        return (ObjectNode) JSON.readTree(Files.readString(p, StandardCharsets.UTF_8));
    }

    @Test
    void cleanSeedHasNoContractErrors() throws Exception {
        DatasetFile file = load(seedRoot().toString());
        assertTrue(DatasetValidator.validate(file).isEmpty());
    }

    @Test
    void duplicateIdRejected() throws Exception {
        ObjectNode root = seedRoot();
        ObjectNode cloned = ((ObjectNode) root.get("samples").get(0)).deepCopy();
        cloned.put("id", "diet");
        ((ArrayNode) root.get("samples")).add(cloned);
        assertEquals(List.of(DatasetValidator.DUPLICATE_ID), DatasetValidator.validate(load(root.toString())));
    }

    @Test
    void missingExpectedRejected() throws Exception {
        ObjectNode root = seedRoot();
        ((ObjectNode) root.get("samples").get(0)).remove("expected");
        assertEquals(List.of(DatasetValidator.MISSING_EXPECTED), DatasetValidator.validate(load(root.toString())));
    }

    @Test
    void unknownPlaceKeyRejected() throws Exception {
        ObjectNode root = seedRoot();
        ObjectNode expected = (ObjectNode) root.get("samples").get(0).get("expected");
        expected.set("forbiddenPlaceKeys", JSON.createArrayNode().add("ATTRACTION:999"));
        assertEquals(List.of(DatasetValidator.UNKNOWN_PLACE_KEY), DatasetValidator.validate(load(root.toString())));
    }

    @Test
    void missingVersionRejected() throws Exception {
        ObjectNode root = seedRoot();
        ((ObjectNode) root.get("samples").get(0)).remove("fixtureVersion");
        assertEquals(List.of(DatasetValidator.MISSING_VERSION), DatasetValidator.validate(load(root.toString())));
    }

    @Test
    void knownPlaceKeysInExpectedAreAccepted() throws Exception {
        ObjectNode root = seedRoot();
        ObjectNode expected = (ObjectNode) root.get("samples").get(0).get("expected");
        expected.set("forbiddenPlaceKeys", JSON.createArrayNode().add("ATTRACTION:2"));
        assertTrue(DatasetValidator.validate(load(root.toString())).isEmpty());
    }

    @Test
    void splitRegistryRejectsCrossSplitSourceGroup() {
        List<DatasetSplits.SplitRow> rows = List.of(
                new DatasetSplits.SplitRow("x1", "trip-x", "development"),
                new DatasetSplits.SplitRow("x2", "trip-x", "holdout"));
        assertEquals(DatasetSplits.DATASET_LEAKAGE, DatasetSplits.leakageCheck(rows));
        assertEquals(false, EvalExperimentGate.canStart(rows));
    }

    @Test
    void splitRegistryAllowsSameSplitSourceGroup() {
        List<DatasetSplits.SplitRow> rows = List.of(
                new DatasetSplits.SplitRow("x1", "trip-x", "development"),
                new DatasetSplits.SplitRow("x2", "trip-x", "development"),
                new DatasetSplits.SplitRow("y1", "trip-y", "holdout"));
        assertEquals(null, DatasetSplits.leakageCheck(rows));
        assertEquals(true, EvalExperimentGate.canStart(rows));
    }
}
