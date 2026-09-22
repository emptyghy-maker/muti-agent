package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.BudgetSpec;
import com.ghy.mutiagent.model.ConstraintEntry;
import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.model.requirement.InterpretationStatus;
import com.ghy.mutiagent.model.requirement.RequirementOperator;
import com.ghy.mutiagent.model.requirement.RequirementScope;
import com.ghy.mutiagent.model.requirement.RequirementSubject;
import com.ghy.mutiagent.model.requirement.RequirementUnit;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 偏好规则解析器：先用确定性规则抽取用户回复，命中不了再回退 PreferenceAgent（LLM）。
 *
 * S02 扩展：
 * - parseResult 除字段更新外，还输出快照约束（老人背景/不能爬山/夜景/减少步行等）与明确撤销条目；
 * - 未消费的残余原文进入 unresolvedText（不能当作没有需求）；
 * - 预算解析顺序：人均/总额 → 区间 → 上限 → 单值，避免「预算\d+」抢先吞掉区间；
 * - 意图区分：口语翻页（PAGING）与附带条件（CONDITION），混合意图二者并存。
 */
@Component
public class RulePreferenceParser {

    private static final List<String> UNSURE_WORDS = List.of(
            "随便", "都行", "随意", "还没想好", "没想好", "不知道",
            "你定", "你决定", "无所谓", "没有", "都听你的", "按你推荐");

    /** 口语「翻页」意图词（与编排器 NO_MORE_WORDS 同步） */
    private static final List<String> PAGING_WORDS = List.of(
            "没有想要的", "都不喜欢", "都不满意", "还有别的", "换一批", "再看看", "都不行", "换一换");

    /** 各字段选项按钮 → 结构化值（必须与编排器 QUESTION_TEMPLATES 的选项保持同步） */
    private static final Map<String, Map<String, String>> OPTION_ANSWERS = Map.of(
            "days", Map.of("1天", "1", "2天", "2", "3天", "3", "4天", "4", "5天及以上", "5"),
            "totalBudget", Map.of("1500以内", "1500", "1500-3000", "2250",
                    "3000-5000", "4000", "5000以上", "6000"),
            "peopleCount", Map.of("1人", "1", "2人", "2", "3-4人", "4", "5人以上", "5"),
            "attractionType", Map.of("打卡拍照", "打卡拍照", "娱乐项目", "娱乐项目",
                    "两者都要", "混合", "按你推荐", "UNSURE"),
            "foodTaste", Map.of("清淡", "清淡", "辣", "辣", "本地特色菜", "本地特色菜", "都行", "UNSURE"),
            "energyLevel", Map.of("体力好", "体力好", "一般", "一般", "偏弱", "偏弱"),
            "hotelStyle", Map.of("性价比优先", "性价比优先", "体验优先", "体验优先",
                    "位置/交通优先", "位置优先", "没想好", "UNSURE"),
            "specialRequests", Map.of("没有", "UNSURE")
    );

    /** 解析用户回复，返回 field → 值 的更新；值 UNSURE 表示用户没想好（兼容旧调用方） */
    public Map<String, String> parse(String message, String currentField) {
        return parseResult(message, currentField, null).getUpdates();
    }

    /**
     * 解析并输出完整结果。
     * @param context 当前偏好（人均预算换算全团口径时使用已确认人数）
     */
    public RuleParseResult parseResult(String message, String currentField, TravelPreference context) {
        RuleParseResult r = new RuleParseResult();
        if (message == null || message.isBlank()) {
            return r;
        }
        String m = message.trim();
        List<int[]> spans = new ArrayList<>();

        // 意图：口语翻页
        for (String w : PAGING_WORDS) {
            int i = m.indexOf(w);
            if (i >= 0) {
                r.getIntents().add("PAGING");
                spans.add(new int[]{i, i + w.length()});
                break;
            }
        }

        // 1. 精确匹配当前问题的选项按钮
        if (currentField != null && OPTION_ANSWERS.containsKey(currentField)) {
            String v = OPTION_ANSWERS.get(currentField).get(m);
            if (v != null) {
                r.getUpdates().put(currentField, v);
                r.getIntents().add("CONDITION");
                return r;
            }
        }

        // 2. 短句婉拒：视为对当前问题的 UNSURE（只影响当前字段，不抹除其他约束）
        if (m.length() <= 8 && UNSURE_WORDS.stream().anyMatch(m::contains)) {
            if (currentField != null && !currentField.isBlank()) {
                r.getUpdates().put(currentField, "UNSURE");
                r.getIntents().add("CONDITION");
            }
            return r;
        }

        // 2.5 当前问题上下文中的纯数值回答：
        // 问“预算多少”答“600”时，数字本身就是预算，不应作为未解析残余进入特殊需求。
        // 天数、人数使用同一规则，并在这里先做范围校验，非法数字仍留给后续澄清。
        if (parseContextualScalar(m, currentField, r)) {
            r.getIntents().add("CONDITION");
            return r;
        }

        // 3. 否定纠正：「不是3天，是5天」→ 5
        Matcher neg = Pattern.compile("不是\\s*(\\d+)\\s*天[，,、\\s]*是?\\s*(\\d+)\\s*天").matcher(m);
        if (neg.find()) {
            r.getUpdates().put("days", neg.group(2));
            addSpan(spans, neg);
        }

        // 4. 关键词抽取（可一次命中多个字段）
        Matcher dm = Pattern.compile("(?:玩|去|待|计划|玩个|待个)?(\\d+)\\s*天").matcher(m);
        if (dm.find()) {
            r.getUpdates().putIfAbsent("days", dm.group(1));
            addSpan(spans, dm);
        }
        Integer messagePeople = null;
        Matcher pm = Pattern.compile("(\\d+)\\s*(?:个)?人").matcher(m);
        if (pm.find()) {
            messagePeople = Integer.valueOf(pm.group(1));
            r.getUpdates().put("peopleCount", pm.group(1));
            addSpan(spans, pm);
        }

        // 5. 预算解析（顺序：人均 → 区间 → 上限 → 单值；区间不被「预算\d+」抢先吞掉）
        extractBudget(m, context, messagePeople, r, spans);

        // 6. 口味/景点类型/体力/酒店偏好（沿用旧关键词规则）
        if (m.contains("打卡") || m.contains("拍照")) {
            r.getUpdates().put("attractionType", "打卡拍照");
        } else if (m.contains("娱乐") || m.contains("游乐") || m.contains("乐园") || m.contains("刺激")) {
            r.getUpdates().put("attractionType", "娱乐项目");
        } else if (m.contains("都要") || m.contains("混合") || m.contains("都有")) {
            r.getUpdates().put("attractionType", "混合");
        }
        if (m.contains("清淡") || m.contains("不辣")) {
            r.getUpdates().put("foodTaste", "清淡");
        } else if (m.contains("辣")) {
            r.getUpdates().put("foodTaste", "辣");
        } else if (m.contains("本地") || m.contains("地道") || m.contains("特色")) {
            r.getUpdates().put("foodTaste", "本地特色菜");
        }
        if (m.contains("体力好") || m.contains("身体好") || m.contains("精力好")) {
            r.getUpdates().put("energyLevel", "体力好");
        } else if (m.contains("偏弱") || m.contains("体力差") || m.contains("走不动")) {
            r.getUpdates().put("energyLevel", "偏弱");
        } else if (m.contains("体力一般") || m.contains("一般般")) {
            r.getUpdates().put("energyLevel", "一般");
        }
        if (m.contains("性价比")) {
            r.getUpdates().put("hotelStyle", "性价比优先");
        } else if (m.contains("体验")) {
            r.getUpdates().put("hotelStyle", "体验优先");
        } else if (m.contains("位置") || m.contains("交通")) {
            r.getUpdates().put("hotelStyle", "位置优先");
        }

        // 7. 快照约束抽取（含明确撤销）；约束原文同时并入 specialRequests 兼容字段
        extractConstraints(m, r, spans);

        // 8. 餐次结构（顿数/早饭午饭晚饭/小吃取舍；明确表达才生效，未命中照旧进残余）
        extractMealPlan(m, r, spans);

        // 8.5 行程偏好问卷字段（起床/回家回酒店时间/活动倾向/夜景数量；命中即消费原文区间）
        extractQuizAnswers(m, r, spans);

        // 9. 未消费残余：只去掉标点空白；有语义残余就保留，不丢弃
        String left = remainder(m, spans);
        if (!left.isBlank()) {
            r.setUnresolvedText(left);
        }
        if (!r.getUpdates().isEmpty() || !r.getConstraints().isEmpty()
                || r.getBudget() != null || !r.getIntents().contains("CONDITION") && r.getUnresolvedText() != null) {
            r.getIntents().add("CONDITION");
        }
        return r;
    }

    /** 只在明确的数值型当前问题下解释无主语短答，避免脱离上下文时把任意数字猜成预算。 */
    private boolean parseContextualScalar(String message, String currentField, RuleParseResult r) {
        if (currentField == null) {
            return false;
        }
        Pattern expected = switch (currentField) {
            case "totalBudget" -> Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*(?:元|块)?\\s*(?:左右|上下)?$");
            case "days" -> Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*天?$");
            case "peopleCount" -> Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*(?:个?人)?$");
            default -> null;
        };
        if (expected == null) {
            return false;
        }
        Matcher scalar = expected.matcher(message);
        if (!scalar.matches()) {
            return false;
        }
        BigDecimal number = new BigDecimal(scalar.group(1));
        switch (currentField) {
            case "totalBudget" -> {
                if (number.signum() <= 0) {
                    return false;
                }
                String value = number.stripTrailingZeros().toPlainString();
                r.getUpdates().put("totalBudget", value);
                BudgetSpec spec = new BudgetSpec();
                spec.setTarget(number);
                spec.setScope("GROUP_TRIP");
                r.setBudget(spec);
                return true;
            }
            case "days" -> {
                try {
                    int value = number.intValueExact();
                    if (value >= 1 && value <= 15) {
                        r.getUpdates().put("days", String.valueOf(value));
                        return true;
                    }
                } catch (ArithmeticException ignored) {
                    // 小数天数或超范围数字不强行解释，交给后续澄清。
                }
            }
            case "peopleCount" -> {
                try {
                    int value = number.intValueExact();
                    if (value >= 1 && value <= 20) {
                        r.getUpdates().put("peopleCount", String.valueOf(value));
                        return true;
                    }
                } catch (ArithmeticException ignored) {
                    // 小数人数或超范围数字不强行解释，交给后续澄清。
                }
            }
            default -> {
                return false;
            }
        }
        return false;
    }

    // ==================== 约束抽取 ====================

    private void extractConstraints(String m, RuleParseResult r, List<int[]> spans) {
        List<String> phrases = new ArrayList<>();

        // 明确撤销：只撤销目标 key
        Matcher revElder = Pattern.compile("(不用|不要|不需要|不考虑)\\s*照顾?\\s*老人").matcher(m);
        if (revElder.find()) {
            r.getConstraints().add(constraint("elderBackground", "TRUE", "SOFT", "REVOKED", revElder.group()));
            addSpan(spans, revElder);
        }
        // 老人背景（排除「不用照顾老人」等撤销表达，撤销在上一分支处理）
        Matcher elder = Pattern.compile("(?<!照顾)(带|有|家里有|同行有)?老人([行动不便腿脚不]\\S{0,6})?").matcher(m);
        if (elder.find()) {
            phrases.add(elder.group().trim());
            r.getConstraints().add(constraint("elderBackground", "TRUE", "SOFT", "ACTIVE", elder.group().trim()));
            addSpan(spans, elder);
        }
        // 不能爬山 / 不爬山（「不爬山」是常见省略说法）
        Matcher climb = Pattern.compile("(不能|不要|不想|别|不)\\s*(去)?爬山").matcher(m);
        if (climb.find()) {
            phrases.add(climb.group().trim());
            r.getConstraints().add(constraint("avoidClimbing", "TRUE", "HARD", "ACTIVE", climb.group().trim()));
            addSpan(spans, climb);
        }
        // 夜景
        Matcher night = Pattern.compile("(还想|想|要看|看|喜欢)?夜景").matcher(m);
        if (night.find()) {
            phrases.add(night.group().trim());
            r.getConstraints().add(constraint("interest", "NIGHT_VIEW", "SOFT", "ACTIVE", night.group().trim()));
            addSpan(spans, night);
        }
        // 减少步行
        Matcher walk = Pattern.compile("(减少|少走|少点|不想多)(步行|走路|走)|不想折腾").matcher(m);
        if (walk.find()) {
            phrases.add(walk.group().trim());
            r.getConstraints().add(constraint("avoidActivity", "WALKING", "SOFT", "ACTIVE", walk.group().trim()));
            addSpan(spans, walk);
        }
        if (!phrases.isEmpty()) {
            r.getUpdates().put("specialRequests", String.join("，", phrases));
        }
    }

    private ConstraintEntry constraint(String key, String value, String hardness, String status, String text) {
        ConstraintEntry c = new ConstraintEntry();
        c.setKey(key);
        c.setValue(value);
        c.setHardness(hardness);
        c.setStatus(status);
        c.setSource("USER");
        c.setOriginalText(text);
        return c;
    }

    // ==================== 行程偏好问卷字段抽取（PLAN_QUIZ 聊天兜底） ====================

    private void extractQuizAnswers(String m, RuleParseResult r, List<int[]> spans) {
        // 起床时间：「9点起床」「8点半起来」「09:00 起床」
        Matcher wake = Pattern.compile("([01]?\\d|2[0-3])[:：点时](半|([0-5]?\\d))?分?\\s*(?:就|要|想|打算)?(?:起来|起床)")
                .matcher(m);
        if (wake.find()) {
            String mm = wake.group(2);
            int min = "半".equals(mm) ? 30
                    : (mm == null || mm.isEmpty() ? 0 : Integer.parseInt(mm));
            String hm = toHm(Integer.parseInt(wake.group(1)), min);
            if (hm.compareTo("05:00") >= 0 && hm.compareTo("12:00") <= 0) {
                r.getUpdates().putIfAbsent("wakeTime", hm);
                addSpan(spans, wake);
            }
        }
        // 回家/回酒店截止：「10点前回家」「22:30 回酒店」「晚上11点回去」
        Matcher deadline = Pattern.compile("([01]?\\d|2[0-3])[:：点时](半|([0-5]?\\d))?分?\\s*(?:之前|以前|前|以内)?"
                        + "\\s*(?:回(?:到)?(?:酒店|家|去)?|到(?:酒店|家)|返程)")
                .matcher(m);
        if (deadline.find()) {
            int hour = Integer.parseInt(deadline.group(1));
            if (hour < 12 && !m.matches(".*(凌晨|早晨|早上).*")) {
                hour += 12; // 「10点回家」口语即晚上 22:00
            }
            String mm = deadline.group(2);
            int min = "半".equals(mm) ? 30
                    : (mm == null || mm.isEmpty() ? 0 : Integer.parseInt(mm));
            String hm = toHm(hour, min);
            if (hm.compareTo("17:00") >= 0 && hm.compareTo("24:00") <= 0) {
                r.getUpdates().putIfAbsent("returnDeadline", hm);
                addSpan(spans, deadline);
            }
        }
        // 活动倾向
        if (m.contains("上午为主") || m.contains("上午多") || m.contains("早点出发") || m.contains("早起")) {
            r.getUpdates().putIfAbsent("activityBias", "MORNING");
        } else if (m.contains("下午晚上") || m.contains("晚上为主") || m.contains("晚点出发")
                || m.contains("不想早起") || m.contains("睡个懒觉")) {
            r.getUpdates().putIfAbsent("activityBias", "EVENING");
        } else if (m.contains("均衡") || m.contains("正常安排")) {
            r.getUpdates().putIfAbsent("activityBias", "BALANCED");
        }
        // 夜景数量：「夜景都要」→ ALL；「只看1个夜景/夜景只要一个」→ ONE
        if (m.contains("夜景都要") || m.contains("都要夜景") || m.contains("所有夜景") || m.contains("全部夜景")) {
            r.getUpdates().putIfAbsent("nightPlan", "ALL");
        } else if (m.contains("只看一个夜景") || m.contains("只要一个夜景") || m.contains("一个夜景就够")) {
            r.getUpdates().putIfAbsent("nightPlan", "ONE");
        }
    }

    private static String toHm(int hour, int min) {
        return String.format("%02d:%02d", Math.min(23, hour), Math.min(59, min));
    }

    // ==================== 餐次结构抽取 ====================

    /**
     * O2 餐次需求：数量必须同时携带 unit 与 scope。
     * 无范围的「2顿午餐」进入 NEEDS_CLARIFICATION，不能再猜成 lunchPerDay。
     */
    private void extractMealPlan(String m, RuleParseResult r, List<int[]> spans) {
        RequirementScope scope = mealScope(m);
        List<int[]> local = new ArrayList<>();
        boolean matched = false;

        // 候选店数量先于餐次匹配，避免「推荐2家午餐店」被当成吃2顿。
        Matcher candidate = Pattern.compile("((?:至少|最少|最多|不超过|至多)?)([一二两三四五六七八九十\\d]+)\\s*家(?:适合)?(午餐|午饭|中饭|晚餐|晚饭)(?:店|餐厅|饭店)?")
                .matcher(m);
        while (candidate.find()) {
            int count = chineseNumber(candidate.group(2));
            RequirementSubject subject = candidate.group(3).startsWith("晚")
                    ? RequirementSubject.DINNER_RESTAURANT : RequirementSubject.LUNCH_RESTAURANT;
            r.getConstraints().add(structuredRequirement(
                    "candidate." + subject.name(), subject, count, operatorOf(candidate.group(1)),
                    RequirementUnit.CANDIDATE_COUNT, RequirementScope.NONE,
                    InterpretationStatus.CONFIRMED, "SOFT", candidate.group(), null));
            addSpan(local, candidate);
            addSpan(spans, candidate);
            matched = true;
        }

        // 菜品数量属于尚未支持的菜品层，明确记录而不是映射成餐厅或餐次。
        Matcher dish = Pattern.compile("((?:至少|最少|最多|不超过|至多)?)([一二两三四五六七八九十\\d]+)\\s*道(?:菜|菜品)")
                .matcher(m);
        while (dish.find()) {
            int count = chineseNumber(dish.group(2));
            r.getConstraints().add(structuredRequirement(
                    "dish.count", RequirementSubject.DISH, count, operatorOf(dish.group(1)),
                    RequirementUnit.DISH_COUNT, RequirementScope.NONE,
                    InterpretationStatus.UNSUPPORTED, "SOFT", dish.group(), "DISH_LEVEL_NOT_SUPPORTED"));
            addSpan(local, dish);
            addSpan(spans, dish);
            matched = true;
        }

        Matcher bfNeg = Pattern.compile("(不要|不吃|不需要|不用|免|取消|省了)\\s*(早(饭|餐))").matcher(m);
        if (bfNeg.find()) {
            r.getUpdates().put("breakfastPerDay", "0");
            r.getConstraints().add(structuredRequirement("meal.BREAKFAST", RequirementSubject.BREAKFAST, 0,
                    RequirementOperator.EQ, RequirementUnit.MEAL_OCCASION,
                    scope == RequirementScope.UNRESOLVED ? RequirementScope.TRIP : scope,
                    InterpretationStatus.CONFIRMED, "HARD", bfNeg.group(), null));
            addSpan(spans, bfNeg);
            matched = true;
        }
        Matcher meal = Pattern.compile("((?:至少|最少|最多|不超过|至多)?)([一二两三四五六七八九十\\d]+)\\s*顿?\\s*(早(?:饭|餐)|午(?:饭|餐)|中(?:饭|餐)|晚(?:饭|餐))")
                .matcher(m);
        while (meal.find()) {
            if (overlaps(local, meal.start(), meal.end())) {
                continue;
            }
            int count = chineseNumber(meal.group(2));
            RequirementSubject subject = mealSubject(meal.group(3));
            InterpretationStatus interpretation = scope == RequirementScope.UNRESOLVED
                    ? InterpretationStatus.NEEDS_CLARIFICATION
                    : subject == RequirementSubject.BREAKFAST && count > 0
                    ? InterpretationStatus.UNSUPPORTED : InterpretationStatus.CONFIRMED;
            String reason = interpretation == InterpretationStatus.NEEDS_CLARIFICATION
                    ? "MEAL_SCOPE_REQUIRED"
                    : interpretation == InterpretationStatus.UNSUPPORTED ? "BREAKFAST_PLANNING_NOT_SUPPORTED" : null;
            r.getConstraints().add(structuredRequirement(
                    "meal." + subject.name(), subject, count, operatorOf(meal.group(1)),
                    RequirementUnit.MEAL_OCCASION, scope, interpretation, "HARD", meal.group(), reason));
            // 只有明确 PER_DAY 才写旧投影字段；TRIP 和 UNRESOLVED 绝不能进入 *PerDay。
            if (scope == RequirementScope.PER_DAY && interpretation == InterpretationStatus.CONFIRMED) {
                String field = switch (subject) {
                    case BREAKFAST -> "breakfastPerDay";
                    case LUNCH -> "lunchPerDay";
                    case DINNER -> "dinnerPerDay";
                    default -> null;
                };
                if (field != null) {
                    r.getUpdates().put(field, String.valueOf(count));
                }
            }
            addSpan(spans, meal);
            matched = true;
        }
        Matcher snNeg = Pattern.compile("(不要|不吃|不需要|不用|免|取消|省了)\\s*(小吃|夜宵)").matcher(m);
        if (snNeg.find()) {
            r.getUpdates().put("snacksAllowed", "false");
            r.getConstraints().add(structuredRequirement("meal.SNACK_ALLOWED", RequirementSubject.SNACK_ALLOWED,
                    null, RequirementOperator.FORBID, RequirementUnit.BOOLEAN, RequirementScope.TRIP,
                    InterpretationStatus.CONFIRMED, "HARD", snNeg.group(), null));
            r.getConstraints().get(r.getConstraints().size() - 1).setValue("FALSE");
            addSpan(spans, snNeg);
            matched = true;
        }
        // 正向表达不得与已消费的否定区间重叠（如「不要小吃」里的「要小吃」不是要小吃）
        Matcher snPos = Pattern.compile("(要|想吃|想尝|加|来|带|留|想要)\\s*点?\\s*(小吃|夜宵)").matcher(m);
        if (snPos.find() && !overlaps(spans, snPos.start(), snPos.end())) {
            r.getUpdates().put("snacksAllowed", "true");
            r.getConstraints().add(structuredRequirement("meal.SNACK_ALLOWED", RequirementSubject.SNACK_ALLOWED,
                    null, RequirementOperator.ALLOW, RequirementUnit.BOOLEAN, RequirementScope.TRIP,
                    InterpretationStatus.CONFIRMED, "SOFT", snPos.group(), null));
            r.getConstraints().get(r.getConstraints().size() - 1).setValue("TRUE");
            addSpan(spans, snPos);
            matched = true;
        }
        if (matched) {
            consumeScopeWords(m, spans);
        }
    }

    private static ConstraintEntry structuredRequirement(String key, RequirementSubject subject, Integer count,
                                                         RequirementOperator operator, RequirementUnit unit,
                                                         RequirementScope scope, InterpretationStatus interpretation,
                                                         String hardness, String text, String reasonCode) {
        ConstraintEntry c = new ConstraintEntry();
        c.setKey(key);
        c.setValue(count == null ? null : String.valueOf(count));
        c.setSubject(subject);
        c.setCount(count);
        c.setOperator(operator);
        c.setUnit(unit);
        c.setScope(scope);
        c.setInterpretationStatus(interpretation);
        c.setHardness(hardness);
        c.setStatus("ACTIVE");
        c.setSource("USER");
        c.setOriginalText(text);
        c.setReasonCode(reasonCode);
        return c;
    }

    private static RequirementScope mealScope(String text) {
        if (Pattern.compile("全程|整个(?:行程|旅程)|这趟(?:旅行|行程)|一共|总共").matcher(text).find()) {
            return RequirementScope.TRIP;
        }
        if (Pattern.compile("每天|每日|每一天|天天").matcher(text).find()) {
            return RequirementScope.PER_DAY;
        }
        return RequirementScope.UNRESOLVED;
    }

    private static void consumeScopeWords(String text, List<int[]> spans) {
        Matcher scope = Pattern.compile("全程|整个(?:行程|旅程)|这趟(?:旅行|行程)|一共|总共|每天|每日|每一天|天天")
                .matcher(text);
        while (scope.find()) {
            addSpan(spans, scope);
        }
    }

    private static RequirementOperator operatorOf(String prefix) {
        if (prefix == null) {
            return RequirementOperator.EQ;
        }
        return switch (prefix) {
            case "至少", "最少" -> RequirementOperator.AT_LEAST;
            case "最多", "不超过", "至多" -> RequirementOperator.AT_MOST;
            default -> RequirementOperator.EQ;
        };
    }

    private static RequirementSubject mealSubject(String raw) {
        if (raw.startsWith("早")) {
            return RequirementSubject.BREAKFAST;
        }
        if (raw.startsWith("晚")) {
            return RequirementSubject.DINNER;
        }
        return RequirementSubject.LUNCH;
    }

    private static int chineseNumber(String raw) {
        if (raw.matches("\\d+")) {
            return Integer.parseInt(raw);
        }
        return switch (raw) {
            case "一" -> 1;
            case "二", "两" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            default -> 0;
        };
    }

    private static boolean overlaps(List<int[]> spans, int start, int end) {
        for (int[] s : spans) {
            if (start < s[1] && end > s[0]) {
                return true;
            }
        }
        return false;
    }

    // ==================== 预算解析 ====================

    private void extractBudget(String m, TravelPreference context, Integer messagePeople,
                               RuleParseResult r, List<int[]> spans) {
        // 1. 人均/每人：优先于总额与区间；全团 = 人均 × 人数
        Matcher perCapita = Pattern.compile("(人均|每人)\\s*(\\d+(?:\\.\\d+)?)").matcher(m);
        if (perCapita.find()) {
            BigDecimal unit = new BigDecimal(perCapita.group(2));
            Integer people = messagePeople != null ? messagePeople
                    : (context != null ? context.getPeopleCount() : null);
            BigDecimal group = people == null ? unit : unit.multiply(BigDecimal.valueOf(people));
            r.getUpdates().put("totalBudget", group.stripTrailingZeros().toPlainString());
            BudgetSpec spec = new BudgetSpec();
            spec.setTarget(group);
            spec.setScope("PER_CAPITA");
            r.setBudget(spec);
            addSpan(spans, perCapita);
            return;
        }
        // 2. 区间（预算前缀或元后缀）：min/max/target=中值；「预算\d+」不得抢先吞掉
        Matcher range = Pattern.compile("预算\\s*(\\d+(?:\\.\\d+)?)\\s*[-—~到至]\\s*(\\d+(?:\\.\\d+)?)"
                + "|(\\d+(?:\\.\\d+)?)\\s*[-—~到至]\\s*(\\d+(?:\\.\\d+)?)\\s*(?:元|块)").matcher(m);
        if (range.find()) {
            BigDecimal min = new BigDecimal(range.group(1) != null ? range.group(1) : range.group(3));
            BigDecimal max = new BigDecimal(range.group(2) != null ? range.group(2) : range.group(4));
            BigDecimal mid = min.add(max).divide(BigDecimal.valueOf(2), 0, java.math.RoundingMode.HALF_UP);
            r.getUpdates().put("totalBudget", mid.toPlainString());
            BudgetSpec spec = new BudgetSpec();
            spec.setMin(min);
            spec.setMax(max);
            spec.setTarget(mid);
            spec.setScope("GROUP_TRIP");
            r.setBudget(spec);
            addSpan(spans, range);
            return;
        }
        // 3. 上限：不超过/以内/上限
        Matcher cap = Pattern.compile("(预算\\s*)?(不超过|不超|上限|最多)\\s*(\\d+(?:\\.\\d+)?)"
                + "|(\\d+(?:\\.\\d+)?)\\s*(以内|之内)").matcher(m);
        if (cap.find()) {
            String v = cap.group(3) != null ? cap.group(3) : cap.group(4);
            r.getUpdates().put("totalBudget", v);
            BudgetSpec spec = new BudgetSpec();
            spec.setMax(new BigDecimal(v));
            spec.setScope("GROUP_TRIP");
            r.setBudget(spec);
            addSpan(spans, cap);
            return;
        }
        // 4. 单值：预算X
        Matcher single = Pattern.compile("预算\\s*(\\d+(?:\\.\\d+)?)").matcher(m);
        if (single.find()) {
            r.getUpdates().put("totalBudget", single.group(1));
            BudgetSpec spec = new BudgetSpec();
            spec.setTarget(new BigDecimal(single.group(1)));
            spec.setScope("GROUP_TRIP");
            r.setBudget(spec);
            addSpan(spans, single);
            return;
        }
        // 5. 兜底：X元/块
        Matcher plain = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:元|块)").matcher(m);
        if (plain.find()) {
            r.getUpdates().put("totalBudget", plain.group(1));
            addSpan(spans, plain);
        }
    }

    // ==================== 跨度与残余 ====================

    private static void addSpan(List<int[]> spans, Matcher m) {
        spans.add(new int[]{m.start(), m.end()});
    }

    /** 去掉已消费区间与标点空白后的残余原文 */
    private static String remainder(String m, List<int[]> spans) {
        if (spans.isEmpty()) {
            return m.trim();
        }
        List<int[]> s = new ArrayList<>(spans);
        s.sort(Comparator.comparingInt(a -> a[0]));
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        for (int[] r : s) {
            if (r[0] < pos) {
                continue;
            }
            sb.append(m, pos, r[0]);
            pos = Math.max(pos, r[1]);
        }
        sb.append(m.substring(pos));
        return sb.toString().replaceAll("[，,。.、；;！!？?\\s]+", "")
                .replaceFirst("^(我)?(要|想要|想|希望|请|帮我|麻烦)", "")
                .replaceFirst("^(改成|改为|变成|换成|要改成|想改成|帮我改成|就改成)", "")
                .replaceFirst("了$", "")
                .trim();
    }
}
