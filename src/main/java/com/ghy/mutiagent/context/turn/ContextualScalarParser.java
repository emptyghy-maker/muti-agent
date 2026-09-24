package com.ghy.mutiagent.context.turn;

import com.ghy.mutiagent.model.TravelPreference;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 使用当前问题解释短数字；只接受与当前字段格式匹配的表达。 */
@Component
public class ContextualScalarParser {

    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");
    private static final Pattern SIMPLE_DAYS = Pattern.compile("^(?:玩|去|安排)?\\s*(\\d{1,2})\\s*(?:天|日)?$");
    private static final Pattern SIMPLE_PEOPLE = Pattern.compile("^(?:共|一共)?\\s*(\\d{1,2})\\s*(?:人|位)?$");
    private static final Pattern SIMPLE_BUDGET = Pattern.compile(
            "^(?:预算|总共|一共|大概|大约)?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:元)?\\s*(?:以内|以下|左右|上下)?$");

    public Map<String, String> parse(String currentField, String message, TravelPreference preference) {
        Map<String, String> out = new LinkedHashMap<>();
        if (currentField == null || message == null || message.isBlank()) {
            return out;
        }
        String text = message.trim();
        if (text.matches("没想好|不知道|随便|都行|按你推荐|无所谓")) {
            out.put(currentField, "UNSURE");
            return out;
        }
        switch (currentField) {
            case "days" -> captureInt(SIMPLE_DAYS, text, 1, 30).ifPresent(v -> out.put("days", v));
            case "peopleCount" -> captureInt(SIMPLE_PEOPLE, text, 1, 50)
                    .ifPresent(v -> out.put("peopleCount", v));
            case "totalBudget" -> parseBudget(text, preference, out);
            case "hotelStyle" -> {
                if (text.matches("不要|不需要|不用|不要酒店|不需要酒店|不用酒店")) {
                    out.put("hotelStyle", "UNSURE");
                }
            }
            default -> {
                // 非数值字段继续交给既有规则和 LLM，避免宽泛猜测。
            }
        }
        return out;
    }

    private static void parseBudget(String text, TravelPreference preference, Map<String, String> out) {
        Matcher perPerson = Pattern.compile("人均\\s*(\\d+(?:\\.\\d+)?)\\s*(?:元)?(?:以内|以下|左右|上下)?")
                .matcher(text);
        if (perPerson.find()) {
            Integer people = peopleInText(text);
            if (people == null) {
                people = preference == null ? null : preference.getPeopleCount();
            }
            if (people != null && people > 0) {
                BigDecimal total = new BigDecimal(perPerson.group(1)).multiply(BigDecimal.valueOf(people));
                out.put("totalBudget", total.stripTrailingZeros().toPlainString());
            }
            return;
        }
        if ((text.contains("人") || text.contains("位")) && !text.contains("预算") && !text.contains("元")) {
            return;
        }
        Matcher matcher = SIMPLE_BUDGET.matcher(text);
        if (matcher.matches()) {
            out.put("totalBudget", new BigDecimal(matcher.group(1)).stripTrailingZeros().toPlainString());
            return;
        }
        // 带明确预算词的自然表达允许从中提取一个数字。
        if (text.contains("预算") || text.contains("花费") || text.contains("元")) {
            Matcher number = NUMBER.matcher(text);
            if (number.find()) {
                out.put("totalBudget", new BigDecimal(number.group(1)).stripTrailingZeros().toPlainString());
            }
        }
    }

    private static Integer peopleInText(String text) {
        Matcher digits = Pattern.compile("(\\d{1,2})\\s*(?:个)?人").matcher(text);
        if (digits.find()) return Integer.parseInt(digits.group(1));
        if (text.matches(".*(?:两|俩)\\s*(?:个)?人.*")) return 2;
        if (text.matches(".*三\\s*(?:个)?人.*")) return 3;
        if (text.matches(".*四\\s*(?:个)?人.*")) return 4;
        return null;
    }

    private static java.util.Optional<String> captureInt(Pattern pattern, String text, int min, int max) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.matches()) return java.util.Optional.empty();
        int value = Integer.parseInt(matcher.group(1));
        return value >= min && value <= max
                ? java.util.Optional.of(String.valueOf(value)) : java.util.Optional.empty();
    }
}
