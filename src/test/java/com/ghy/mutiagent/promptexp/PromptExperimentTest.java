package com.ghy.mutiagent.promptexp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.model.TravelPreference;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * O3 Prompt 对比实验（before/after 同输入同模型）。
 *
 * 使用方式（真实模型，需环境变量 QWEN_API_KEY，仅走环境变量）：
 *   mvn -Dtest=PromptExperimentTest -Dprompt.exp.live=true test
 *     -Dprompt.exp.dir=<Prompt目录>      # arm 差异来源：基线=experiments/EXP-O3-PROMPT/prompts-baseline
 *     -Dprompt.exp.case=A1|A2|B         # 单用例运行（每次调用约 1~5 分钟，建议分次跑）
 *     -Dprompt.exp.repeat=0|1|2         # 单次重复
 *     -Dprompt.exp.stub=true            # 无密钥管道烟测（不产生真实模型调用，结果标 STUB）
 *
 * 记录字段：arm/caseId/repeatIndex/model/promptBundleHash/systemChars/userChars/durationMs/
 * promptTokens/outputTokens/strictJson/tolerantJson/判据逐项/rawChars/stub。
 * 结果追加写入 experiments/EXP-O3-PROMPT/runs.jsonl（路径可用 -Dprompt.exp.out 覆盖）。
 *
 * 消息组装镜像生产代码（ItineraryAgent/FoodAgent 模板 + CandidateService/ItineraryService 组装器，
 * 基线 commit 见 experiments/EXP-O3-PROMPT/README.md），两臂唯一差异 = Prompt 文件内容。
 */
class PromptExperimentTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 实验固定输入（与生产真实会话 77be898945684da7 同构，脱敏合成） */
    private static final List<Map<String, Object>> ATTRACTIONS = List.of(
            attr(43L, "金鸡湖月光码头", "打卡拍照", 1, 1.5, 0, 8.0, "夜景,湖景,情侣,氛围"),
            attr(38L, "十全街", "打卡拍照", 1, 2.0, 0, 7.0, ""),
            attr(24L, "平江路历史街区", "打卡拍照", 2, 3.0, 0, 6.5, ""));

    /** A3 用例：两个夜景标签景点 + 问卷截止时间（夜景系数最高=26 金鸡湖景区 3+2+1） */
    private static final List<Map<String, Object>> ATTRACTIONS_NIGHT = List.of(
            attr(43L, "金鸡湖月光码头", "打卡拍照", 1, 1.5, 0, 8.0, "夜景,湖景,情侣,氛围"),
            attr(26L, "金鸡湖景区", "打卡拍照", 1, 3.0, 0, 9.0, "夜景,音乐喷泉,摩天轮"),
            attr(38L, "十全街", "打卡拍照", 1, 2.0, 0, 7.0, ""));

    private static final List<Map<String, Object>> RESTAURANTS = List.of(
            food(17L, "黄天源糕团(观前街店)", "本地菜", 6.0),
            food(22L, "蜀大侠火锅(观前店)", "火锅", 5.5));

    private static final List<Map<String, Object>> FOOD_POOL = List.of(
            poolItem(1L, "新梅华餐厅(金鸡湖店)", "本地菜", "50", "园区金鸡湖", 4.6),
            poolItem(2L, "阳澄湖农家乐(唯亭店)", "农家菜", "80", "园区唯亭", 4.4),
            poolItem(3L, "苏式面馆", "面馆", "25", "观前街", 4.6),
            poolItem(4L, "老苏州茶酒楼(平江路店)", "本地菜", "70", "平江路", 4.7),
            poolItem(5L, "西园寺素面", "素斋", "30", "西园路", 4.5),
            poolItem(6L, "藏书羊肉馆", "羊肉", "90", "木渎", 4.3),
            poolItem(7L, "太湖船菜", "湖鲜", "110", "太湖边", 4.2),
            poolItem(8L, "得月楼(观前店)", "本地菜", "100", "观前街", 4.8));

    private static Map<String, Object> attr(long id, String name, String category,
                                            int intensity, double hours, int price, double score, String tags) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("category", category);
        m.put("tags", tags);
        m.put("intensity", intensity);
        m.put("hours", hours);
        m.put("price", price);
        m.put("score", score);
        return m;
    }

    private static Map<String, Object> food(long id, String name, String cuisine, double score) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("cuisine", cuisine);
        m.put("score", score);
        return m;
    }

    private static Map<String, Object> poolItem(long id, String name, String cuisine,
                                                String price, String address, double rating) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("cuisine", cuisine);
        m.put("avgPrice", new BigDecimal(price));
        m.put("signatureDish", "");
        m.put("address", address);
        m.put("rating", rating);
        return m;
    }

    @Test
    void promptExperiment() throws Exception {
        boolean stub = Boolean.parseBoolean(System.getProperty("prompt.exp.stub", "false"));
        boolean live = Boolean.parseBoolean(System.getProperty("prompt.exp.live", "false"));
        Assumptions.assumeTrue(stub || live,
                "跳过：Prompt 实验默认关闭；真实实验需显式传 -Dprompt.exp.live=true，管道烟测传 -Dprompt.exp.stub=true");
        String key = System.getenv("QWEN_API_KEY");
        if (!stub) {
            Assumptions.assumeTrue(key != null && !key.isBlank(),
                    "跳过：未设置环境变量 QWEN_API_KEY（真实模型实验需要；管道烟测用 -Dprompt.exp.stub=true）");
        }
        String promptDir = System.getProperty("prompt.exp.dir", "src/main/resources/prompts");
        String outDir = System.getProperty("prompt.exp.out",
                "../suggestion/agent-optimization/experiments/EXP-O3-PROMPT");
        String onlyCase = System.getProperty("prompt.exp.case", "ALL").trim();
        int onlyRepeat = Integer.parseInt(System.getProperty("prompt.exp.repeat", "-1"));

        Path dir = Path.of(promptDir);
        String common = read(dir.resolve("common-format.txt"));
        String itinerary = read(dir.resolve("itinerary.txt"));
        String food = read(dir.resolve("food.txt"));
        String bundleHash = promptBundleHash(dir, List.of("common-format.txt", "itinerary.txt", "food.txt"));
        String arm = System.getProperty("prompt.exp.arm",
                dir.toAbsolutePath().toString().contains("prompts-baseline") ? "before" : "after");
        String reasoningEffort = envOr("QWEN_EXP_REASONING_EFFORT", "none");

        ChatModel sqlModel = stub ? null : OpenAiChatModel.builder()
                .baseUrl(envOr("QWEN_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"))
                .apiKey(key)
                // 成本控制：默认用低价模型跑对比实验（两臂同模型，归因不受影响）；
                // 如需换模型：环境变量 QWEN_EXP_MODEL_SQL / QWEN_EXP_MODEL_DEFAULT 覆盖
                // 评测直接创建单一物理模型，不经过生产 FailoverChatModel；使用带日期版本防止别名漂移。
                .modelName(envOr("QWEN_EXP_MODEL_SQL", "qwen3.8-27b"))
                .defaultRequestParameters(OpenAiChatRequestParameters.builder()
                        .reasoningEffort(reasoningEffort)
                        .build())
                .temperature(0.1)
                .maxTokens(1024)
                .timeout(Duration.ofSeconds(300))
                .maxRetries(0)
                .responseFormat("json_object")
                .build();
        ChatModel flashModel = stub ? null : OpenAiChatModel.builder()
                .baseUrl(envOr("QWEN_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"))
                .apiKey(key)
                .modelName(envOr("QWEN_EXP_MODEL_DEFAULT", "qwen3.7-flash-2026-07-15"))
                .temperature(0.2)
                .maxTokens(2048)
                .timeout(Duration.ofSeconds(180))
                .maxRetries(0)
                .responseFormat("json_object")
                .build();

        Path runs = Path.of(outDir).resolve("runs.jsonl");
        Files.createDirectories(runs.getParent());
        String mainModel = envOr("QWEN_EXP_MODEL_SQL", "qwen3.8-27b");
        String candModel = envOr("QWEN_EXP_MODEL_DEFAULT", "qwen3.7-flash-2026-07-15");
        for (int r = 0; r < 3; r++) {
            if (onlyRepeat >= 0 && onlyRepeat != r) {
                continue;
            }
            if (onlyCase.equals("ALL") || onlyCase.equals("A1")) {
                runCase("A1", arm, bundleHash, r, stub, mainModel, sqlModel,
                        reasoningEffort, itinerary, itineraryUser("A1", rules("A1")), runs);
            }
            if (onlyCase.equals("ALL") || onlyCase.equals("A2")) {
                runCase("A2", arm, bundleHash, r, stub, mainModel, sqlModel,
                        reasoningEffort, itinerary, itineraryUser("A2", rules("A2")), runs);
            }
            if (onlyCase.equals("ALL") || onlyCase.equals("A3")) {
                runCase("A3", arm, bundleHash, r, stub, mainModel, sqlModel,
                        reasoningEffort, itinerary, itineraryUser("A3", rules("A3")), runs);
            }
            if (onlyCase.equals("ALL") || onlyCase.equals("B")) {
                runCase("B", arm, bundleHash, r, stub, candModel, flashModel,
                        null, common + "\n\n" + food, foodUser(), runs);
            }
        }
    }

    private void runCase(String caseId, String arm, String bundleHash, int repeat, boolean stub,
                         String modelName, ChatModel model, String reasoningEffort,
                         String system, String user, Path runs)
            throws Exception {
        String content;
        long durationMs;
        int promptTokens;
        int outputTokens;
        if (stub) {
            content = stubContent(caseId);
            durationMs = 1;
            promptTokens = 1000;
            outputTokens = 800;
        } else {
            long t0 = System.nanoTime();
            ChatResponse resp = model.chat(List.of(
                    (ChatMessage) SystemMessage.from(system),
                    (ChatMessage) UserMessage.from(user)));
            content = resp.aiMessage() == null ? null : resp.aiMessage().text();
            durationMs = (System.nanoTime() - t0) / 1_000_000;
            promptTokens = resp.tokenUsage() == null || resp.tokenUsage().inputTokenCount() == null
                    ? -1 : resp.tokenUsage().inputTokenCount();
            outputTokens = resp.tokenUsage() == null || resp.tokenUsage().outputTokenCount() == null
                    ? -1 : resp.tokenUsage().outputTokenCount();
        }
        assertNotNull(content, "模型无输出");

        Parse parse = parse(content);
        Map<String, Object> criteria = judge(caseId, parse.node);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("arm", arm);
        row.put("caseId", caseId);
        row.put("repeatIndex", repeat);
        row.put("model", modelName);
        row.put("reasoningEffort", reasoningEffort);
        row.put("promptBundleHash", bundleHash);
        row.put("systemChars", system.length());
        row.put("userChars", user.length());
        row.put("durationMs", durationMs);
        row.put("promptTokens", promptTokens);
        row.put("outputTokens", outputTokens);
        row.put("strictJson", parse.strict);
        row.put("tolerantJson", parse.tolerant);
        row.put("rawChars", content == null ? -1 : content.length());
        row.putAll(criteria);
        row.put("stub", stub);
        String line = JSON.writeValueAsString(row) + "\n";
        Files.writeString(runs, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println("[EXP] " + row);
    }

    // ---------------- 消息组装（镜像生产） ----------------

    private String itineraryUser(String caseId, String rules) throws Exception {
        String pref = JSON.writeValueAsString(Map.of(
                "days", 1, "energyLevel", "中等", "activityBias", "BALANCED",
                "nightPlan", "ONE", "returnDeadline", "22:00"));
        String attr = JSON.writeValueAsString("A3".equals(caseId) ? ATTRACTIONS_NIGHT : ATTRACTIONS);
        String food = JSON.writeValueAsString(RESTAURANTS);
        String hotel = JSON.writeValueAsString(List.of());
        return "规划偏好：" + pref
                + "\n\n已确认景点（必须全部安排，只可用其中 id）：" + attr
                + "\n\n已确认餐厅（只可用其中 id）：" + food
                + "\n\n住宿锚点（仅辅助判断片区，不要输出）：" + hotel
                + "\n\n规则要求：" + rules
                + "\n\n输出格式：json";
    }

    private String foodUser() throws Exception {
        TravelPreference p = new TravelPreference();
        p.setDays(2);
        p.setTotalBudget(new BigDecimal("3000"));
        p.setPeopleCount(2);
        p.setFoodTaste("本地特色菜");
        String pref = JSON.writeValueAsString(p)
                + "\n\n用户额外要求：苏州工业园区，要有氛围感的饭店";
        return "候选池（每家店含 cuisine 风味字段）：" + JSON.writeValueAsString(FOOD_POOL)
                + "\n\n用户偏好：" + pref
                + "\n\n目标数量：6 家左右\n\n输出格式：json";
    }

    /** 镜像 ItineraryService.buildRules：只传影响分天、顺序与餐次的动态规则。 */
    private String rules(String caseId) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("- 共 1 天，只决定 attraction/restaurant 的分天与先后顺序。\n");
        sb.append("- 已确认景点必须全部且各输出一次，不能遗漏。\n");
        sb.append("- 餐次策略：每天恰好1顿午餐；每天恰好1顿晚餐；restaurant.note 只能写午餐或晚餐，同一餐厅全程最多一次。\n");
        sb.append("- 分配倾向：上午1个、下午1个、晚上1个；openTime 早结束的景点优先，夜景标签景点排在晚餐之后。\n");
        sb.append("- 晚餐后只保留 1 个夜景景点，优先 id=")
                .append("A3".equals(caseId) ? 26 : 43).append("。\n");
        if ("A2".equals(caseId)) {
            String original = JSON.writeValueAsString(Map.of("days", List.of(Map.of(
                    "dayIndex", 1, "theme", "园区浪漫打卡", "nodes", List.of(
                            Map.of("type", "attraction", "placeId", 43),
                            Map.of("type", "restaurant", "placeId", 17, "note", "午餐"),
                            Map.of("type", "attraction", "placeId", 38),
                            Map.of("type", "restaurant", "placeId", 22, "note", "晚餐"),
                            Map.of("type", "attraction", "placeId", 24))))));
            sb.append("\n\n【用户调整诉求与原行程】\n")
                    .append("用户调整诉求：把平江路历史街区放在第一站\n\n原行程：").append(original)
                    .append("\n只调整涉及的地点顺序，仍只输出决策骨架；其他内容保持原顺序。");
        }
        return sb.toString().trim();
    }

    // ---------------- 判据（确定性，两臂一致） ----------------

    private record Parse(boolean strict, boolean tolerant, JsonNode node) {
    }

    private Parse parse(String content) {
        boolean strict = true;
        JsonNode node = null;
        try {
            node = JSON.readTree(content);
        } catch (Exception e) {
            strict = false;
        }
        if (node == null) {
            try {
                String stripped = content.replaceAll("^```[a-zA-Z]*\\s*", "")
                        .replaceAll("\\s*```$", "").trim();
                node = JSON.readTree(stripped);
            } catch (Exception ignored) {
                node = null;
            }
        }
        return new Parse(strict, node != null, node);
    }

    private Map<String, Object> judge(String caseId, JsonNode node) {
        Map<String, Object> c = new LinkedHashMap<>();
        if (node == null) {
            c.put("parseable", false);
            return c;
        }
        c.put("parseable", true);
        if ("B".equals(caseId)) {
            JsonNode items = node.path("items");
            boolean allInPool = items.isArray();
            Set<Long> poolIds = Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
            if (items.isArray()) {
                for (JsonNode it : items) {
                    long id = it.path("restaurantId").asLong(0);
                    if (id == 0 || !poolIds.contains(id)) {
                        allInPool = false;
                    }
                }
            }
            c.put("itemsNonEmpty", items.isArray() && items.size() > 0);
            c.put("allIdsInPool", allInPool);
            c.put("adviceNonEmpty", !node.path("advice").asText("").isBlank());
            return c;
        }
        // A1 / A2 / A3：规划 Agent 只输出决策节点，时间与基础设施由 Java 补齐。
        JsonNode days = node.path("days");
        boolean oneDay = days.isArray() && days.size() == 1;
        List<Long> attractionIds = new ArrayList<>();
        boolean lunch = false;
        boolean dinner = false;
        int lunchCount = 0;
        int dinnerCount = 0;
        long nightSpotId = "A3".equals(caseId) ? 26L : 43L;
        int dinnerIndex = -1;
        int preferredNightIndex = -1;
        int infrastructureNodes = 0;
        int nodesWithTime = 0;
        if (oneDay && days.get(0).path("nodes").isArray()) {
            JsonNode nodes = days.get(0).path("nodes");
            for (int i = 0; i < nodes.size(); i++) {
                JsonNode n = nodes.get(i);
                String type = n.path("type").asText("");
                String note = n.path("note").asText("");
                if (n.hasNonNull("time")) {
                    nodesWithTime++;
                }
                if ("transport".equals(type) || "hotel".equals(type) || "rest".equals(type)) {
                    infrastructureNodes++;
                }
                if ("attraction".equals(type) && n.path("placeId").isNumber()) {
                    long pid = n.path("placeId").asLong();
                    attractionIds.add(pid);
                    if (pid == nightSpotId) {
                        preferredNightIndex = i;
                    }
                }
                if ("restaurant".equals(type)) {
                    if (note.contains("午餐")) {
                        lunch = true;
                        lunchCount++;
                    }
                    if (note.contains("晚餐")) {
                        dinner = true;
                        dinnerCount++;
                        dinnerIndex = i;
                    }
                }
            }
        }
        c.put("oneDay", oneDay);
        c.put("decisionOnly", infrastructureNodes == 0 && nodesWithTime == 0);
        c.put("infrastructureNodes", infrastructureNodes);
        c.put("nodesWithTime", nodesWithTime);
        c.put("allThreeAttractions", attractionIds.containsAll("A3".equals(caseId)
                ? List.of(43L, 26L, 38L) : List.of(43L, 38L, 24L)));
        c.put("lunchPresent", lunch);
        c.put("dinnerPresent", dinner);
        c.put("lunchCount", lunchCount);
        c.put("dinnerCount", dinnerCount);
        c.put("preferredNightAfterDinner", preferredNightIndex > dinnerIndex && dinnerIndex >= 0);
        if ("A2".equals(caseId)) {
            c.put("requestedFirstAttraction", !attractionIds.isEmpty() && attractionIds.get(0) == 24L);
        }
        return c;
    }

    private String stubContent(String caseId) throws Exception {
        if ("B".equals(caseId)) {
            return "{\"items\":[{\"restaurantId\":1},{\"restaurantId\":2}],\"advice\":\"园区店有限，建议扩大范围\"}";
        }
        long first = "A3".equals(caseId) ? 43L : 24L;
        long last = "A3".equals(caseId) ? 26L : 43L;
        return "{\"days\":[{\"dayIndex\":1,\"theme\":\"园区浪漫打卡\",\"nodes\":["
                + "{\"type\":\"attraction\",\"placeId\":" + first + "},"
                + "{\"type\":\"restaurant\",\"placeId\":17,\"note\":\"午餐\"},"
                + "{\"type\":\"attraction\",\"placeId\":38},"
                + "{\"type\":\"restaurant\",\"placeId\":22,\"note\":\"晚餐\"},"
                + "{\"type\":\"attraction\",\"placeId\":" + last + "}]}]}";
    }

    // ---------------- 工具 ----------------

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }

    private static String promptBundleHash(Path dir, List<String> files) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String f : files) {
                md.update(f.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(Files.readAllBytes(dir.resolve(f)));
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "HASH_ERROR";
        }
    }
}
