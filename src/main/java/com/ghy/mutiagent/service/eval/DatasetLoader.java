package com.ghy.mutiagent.service.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据集加载器（手册 §3.1/§3.2）：读固定种子文件，计算 datasetHash（原始字节 SHA-256），
 * 宽容解析出样本（保持文件顺序），并给出结构级错误与 fixture 可执行性错误。
 *
 * 加载器不因单个坏样本拒绝整个文件：坏样本仍进入 samples，由 DatasetValidator 报契约错误，
 * 保证「发模型请求前拒绝」这一道关在验证器上统一执行。
 * 每次新增/修改样本生成新 hash；历史运行保留原快照（hash 即内容指纹）。
 */
public final class DatasetLoader {

    public static final String INVALID_JSON = "INVALID_JSON";
    public static final String MISSING_SAMPLES = "MISSING_SAMPLES";
    public static final String SAMPLE_NOT_OBJECT = "SAMPLE_NOT_OBJECT";
    public static final String MISSING_TOOL_SNAPSHOT = "MISSING_TOOL_SNAPSHOT";

    private static final ObjectMapper JSON = new ObjectMapper();

    private DatasetLoader() {
    }

    public static DatasetFile load(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        return parse(new String(bytes, StandardCharsets.UTF_8), sha256(bytes));
    }

    public static DatasetFile parse(String rawJson, String datasetHash) {
        List<String> schemaErrors = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(rawJson);
            if (root == null || !root.isObject()) {
                schemaErrors.add(INVALID_JSON);
                return empty(datasetHash, schemaErrors);
            }
            JsonNode samplesNode = root.get("samples");
            if (samplesNode == null || !samplesNode.isArray()) {
                schemaErrors.add(MISSING_SAMPLES);
            }
            DatasetToolSnapshot snapshot = toolSnapshot(root.get("toolSnapshot"), schemaErrors);
            List<DatasetSample> samples = new ArrayList<>();
            if (samplesNode != null && samplesNode.isArray()) {
                for (JsonNode n : samplesNode) {
                    if (n == null || !n.isObject()) {
                        schemaErrors.add(SAMPLE_NOT_OBJECT);
                        continue;
                    }
                    samples.add(DatasetSample.fromJson(n));
                }
            }
            List<String> fixtureErrors = new ArrayList<>();
            for (DatasetSample s : samples) {
                if (!isBlank(s.fixtureVersion())
                        && (snapshot == null || !s.fixtureVersion().equals(snapshot.id()))) {
                    fixtureErrors.add("sample " + s.id() + " fixture " + s.fixtureVersion()
                            + " 不在本数据集可执行 fixture 中");
                }
            }
            return new DatasetFile(
                    text(root, "schemaVersion"),
                    text(root, "datasetId"),
                    text(root, "version"),
                    text(root, "split"),
                    root.path("synthetic").asBoolean(false),
                    text(root, "providerMode"),
                    text(root, "description"),
                    snapshot,
                    samples,
                    datasetHash,
                    schemaErrors,
                    fixtureErrors);
        } catch (Exception e) {
            schemaErrors.add(INVALID_JSON);
            return empty(datasetHash, schemaErrors);
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static DatasetToolSnapshot toolSnapshot(JsonNode node, List<String> schemaErrors) {
        if (node == null || !node.isObject()) {
            schemaErrors.add(MISSING_TOOL_SNAPSHOT);
            return null;
        }
        List<String> keys = new ArrayList<>();
        List<java.util.Map<String, Object>> places = new ArrayList<>();
        if (node.get("places") != null && node.get("places").isArray()) {
            for (JsonNode p : node.get("places")) {
                if (p == null || !p.isObject()) {
                    continue;
                }
                String key = p.path("key").asText(null);
                if (key != null) {
                    keys.add(key);
                }
                places.add(JSON.convertValue(p,
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, Object>>() {
                        }));
            }
        }
        return new DatasetToolSnapshot(
                text(node, "id"),
                text(node, "date"),
                text(node, "city"),
                text(node, "weather"),
                keys,
                places);
    }

    private static DatasetFile empty(String datasetHash, List<String> schemaErrors) {
        return new DatasetFile(null, null, null, null, false, null, null,
                null, List.of(), datasetHash, schemaErrors, List.of());
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
