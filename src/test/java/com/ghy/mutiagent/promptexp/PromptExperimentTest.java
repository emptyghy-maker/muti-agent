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
 *   mvn -Dtest=PromptExperimentTest test
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
            attr(43L, "金鸡湖月光码头", "打卡拍照", 1, 1.5, 0, 8.0),
            attr(38L, "十全街", "打卡拍照", 1, 2.0, 0, 7.0),
            attr(24L, "平江路历史街区", "打卡拍照", 2, 3.0, 0, 6.5));

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
                                            int intensity, double hours, int price, double score) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("category", category);
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

        ChatModel sqlModel = stub ? null : OpenAiChatModel.builder()
                .baseUrl(envOr("QWEN_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"))
                .apiKey(key)
                // 成本控制：默认用低价模型跑对比实验（两臂同模型，归因不受影响）；
                // 如需换模型：环境变量 QWEN_EXP_MODEL_SQL / QWEN_EXP_MODEL_DEFAULT 覆盖
                .modelName(envOr("QWEN_EXP_MODEL_SQL", "qwen3.7-flash"))
                .temperature(0.1)
                .maxTokens(2048)
                .timeout(Duration.ofSeconds(300))
                .maxRetries(0)
                .responseFormat("json_object")
                .build();
        ChatModel flashModel = stub ? null : OpenAiChatModel.builder()
                .baseUrl(envOr("QWEN_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"))
                .apiKey(key)
                .modelName(envOr("QWEN_EXP_MODEL_DEFAULT", "qwen3.7-flash"))
                .temperature(0.2)
                .maxTokens(2048)
                .timeout(Duration.ofSeconds(180))
                .maxRetries(0)
                .responseFormat("json_object")
                .build();

        Path runs = Path.of(outDir).resolve("runs.jsonl");
        Files.createDirectories(runs.getParent());
        String mainModel = envOr("QWEN_EXP_MODEL_SQL", "qwen3.7-flash");
        String candModel = envOr("QWEN_EXP_MODEL_DEFAULT", "qwen3.7-flash");
        for (int r = 0; r < 3; r++) {
            if (onlyRepeat >= 0 && onlyRepeat != r) {
                continue;
            }
            if (onlyCase.equals("ALL") || onlyCase.equals("A1")) {
                runCase("A1", arm, bundleHash, r, stub, mainModel, sqlModel,
                        itinerary, itineraryUser("A1", rules("A1")), runs);
            }
            if (onlyCase.equals("ALL") || onlyCase.equals("A2")) {
                runCase("A2", arm, bundleHash, r, stub, mainModel, sqlModel,
                        itinerary, itineraryUser("A2", rules("A2")), runs);
            }
            if (onlyCase.equals("ALL") || onlyCase.equals("B")) {
                runCase("B", arm, bundleHash, r, stub, candModel, flashModel,
                        common + "\n\n" + food, foodUser(), runs);
            }
        }
    }

    private void runCase(String caseId, String arm, String bundleHash, int repeat, boolean stub,
                         String modelName, ChatModel model, String system, String user, Path runs)
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
        TravelPreference p = new TravelPreference();
        p.setDays(1);
        p.setTotalBudget(new BigDecimal("3000"));
        p.setPeopleCount(2);
        p.setFoodTaste("本地特色菜");
        p.setAttractionType("打卡拍照");
        p.setEnergyLevel("中等");
        String pref = JSON.writeValueAsString(p);
        String attr = JSON.writeValueAsString(ATTRACTIONS);
        String food = JSON.writeValueAsString(RESTAURANTS);
        String hotel = JSON.writeValueAsString(List.of());
        return "用户偏好：" + pref
                + "\n\n可选景点（只可用其中 id）：" + attr
                + "\n\n可选美食：" + food
                + "\n\n酒店：" + hotel
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

    /** 镜像 ItineraryService.buildRules（基线 commit 版本；两臂共用，不是实验变量） */
    private String rules(String caseId) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("- 共 1 天；首日以 transport 节点启程（抵达），末日以 transport 节点返程。\n");
        sb.append("- 用户不需要酒店：不要安排 hotel 节点，住宿费用按 0 计；每天以当日首个景点为起点和终点。\n");
        sb.append("- 用户已确认的景点必须全部安排进行程（缺一不可）；只有受硬约束（开放时间、劳累度上限）确实无法安排时才能少排，且必须在当天的 theme 或相应 note 中写明原因，不得静默遗漏。\n");
        sb.append("- 每天 11:30-13:30 之间安排 1 个 restaurant 节点（午餐），17:30-19:30 之间安排 1 个 restaurant 节点（晚餐）；车程中不安排用餐。\n");
        sb.append("- 高强度景点与低强度景点错开安排；如某天安排较满，可插入 1 个 rest 节点（候选休息点：）。\n");
        sb.append("- 全程预算约 3000 元，2 人：餐费按人均价×人数计算，餐饮+门票+交通合计不得超过预算；超预算时优先选择人均价更低的餐厅。");
        if ("A2".equals(caseId)) {
            String original = JSON.writeValueAsString(Map.of("days", List.of(Map.of(
                    "dayIndex", 1, "theme", "园区浪漫打卡", "nodes", List.of(
                            Map.of("type", "transport", "time", "09:00", "note", "抵达苏州"),
                            Map.of("type", "attraction", "placeId", 43, "time", "09:30", "note", "湖畔打卡"),
                            Map.of("type", "restaurant", "placeId", 17, "time", "11:30", "note", "午餐"),
                            Map.of("type", "attraction", "placeId", 38, "time", "13:15", "note", "街区拍照"),
                            Map.of("type", "restaurant", "placeId", 22, "time", "17:30", "note", "晚餐"),
                            Map.of("type", "transport", "time", "20:00", "note", "返程"))))));
            sb.append("\n\n【用户调整诉求与原行程】\n")
                    .append("用户调整诉求：我要21:30返程\n\n原行程：").append(original)
                    .append("\n请在原行程基础上做局部调整（其余天数保持不变或仅微调），并同样遵守以上规则。")
                    .append("如果调整诉求改变的是时间窗口（如返程时间推迟或提前），必须综合重排当天的节点时间与停留时长：")
                    .append("把多出的时间合理分配给各节点（延长停留、更从容的用餐），并优先补回此前未安排的已选景点；不要只改动一个节点。");
        }
        return sb.toString();
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
        // A1 / A2 行程判据
        JsonNode days = node.path("days");
        boolean oneDay = days.isArray() && days.size() == 1;
        List<Long> attractionIds = new ArrayList<>();
        boolean firstTransport = false;
        boolean lastTransport = false;
        boolean lunch = false;
        boolean dinner = false;
        String lastTime = "";
        if (oneDay && days.get(0).path("nodes").isArray()) {
            JsonNode nodes = days.get(0).path("nodes");
            for (int i = 0; i < nodes.size(); i++) {
                JsonNode n = nodes.get(i);
                String type = n.path("type").asText("");
                String time = n.path("time").asText("");
                String note = n.path("note").asText("");
                if (i == 0 && "transport".equals(type)) {
                    firstTransport = true;
                }
                if ("transport".equals(type)) {
                    lastTransport = i == nodes.size() - 1;
                    lastTime = time;
                }
                if ("attraction".equals(type) && n.path("placeId").isNumber()) {
                    attractionIds.add(n.path("placeId").asLong());
                }
                if ("restaurant".equals(type)) {
                    if (note.contains("午餐")) {
                        lunch = true;
                    }
                    if (note.contains("晚餐")) {
                        dinner = true;
                    }
                }
            }
        }
        c.put("oneDay", oneDay);
        c.put("firstTransport", firstTransport);
        c.put("lastTransport", lastTransport);
        c.put("allThreeAttractions", attractionIds.containsAll(List.of(43L, 38L, 24L)));
        c.put("lunchPresent", lunch);
        c.put("dinnerPresent", dinner);
        if ("A2".equals(caseId)) {
            c.put("returnAtLeast2130", lastTime.compareTo("21:30") >= 0);
        }
        return c;
    }

    private String stubContent(String caseId) throws Exception {
        if ("B".equals(caseId)) {
            return "{\"items\":[{\"restaurantId\":1},{\"restaurantId\":2}],\"advice\":\"园区店有限，建议扩大范围\"}";
        }
        String lastTime = "A2".equals(caseId) ? "21:30" : "20:00";
        return "{\"days\":[{\"dayIndex\":1,\"theme\":\"园区浪漫打卡\",\"nodes\":["
                + "{\"type\":\"transport\",\"time\":\"09:00\",\"note\":\"抵达苏州\"},"
                + "{\"type\":\"attraction\",\"placeId\":43,\"time\":\"09:30\",\"note\":\"湖畔打卡\"},"
                + "{\"type\":\"restaurant\",\"placeId\":17,\"time\":\"11:30\",\"note\":\"午餐\"},"
                + "{\"type\":\"attraction\",\"placeId\":38,\"time\":\"13:15\",\"note\":\"街区拍照\"},"
                + "{\"type\":\"attraction\",\"placeId\":24,\"time\":\"15:30\",\"note\":\"古城漫步\"},"
                + "{\"type\":\"restaurant\",\"placeId\":22,\"time\":\"17:30\",\"note\":\"晚餐\"},"
                + "{\"type\":\"transport\",\"time\":\"" + lastTime + "\",\"note\":\"返程\"}]}]}";
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
