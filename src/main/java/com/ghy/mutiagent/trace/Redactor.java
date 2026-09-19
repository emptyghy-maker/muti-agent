package com.ghy.mutiagent.trace;

import java.util.regex.Pattern;

/**
 * S12 脱敏：字段级白名单之外的敏感模式统一遮蔽。
 * 覆盖 长 token、手机号、邮箱 三类合成标记；对 question、answer、error、
 * 日志、导出等所有出口使用同一函数，保证出口口径一致。
 * 不允许通过直接拼接原始字段绕过脱敏。
 */
public final class Redactor {

    public static final String REDACTED = "[REDACTED]";

    private static final Pattern TOKEN = Pattern.compile("\\b[A-Z0-9_]{16,}\\b");
    private static final Pattern PHONE = Pattern.compile("\\b1[3-9]\\d{9}\\b");
    private static final Pattern EMAIL = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");

    private Redactor() {
    }

    /** 遮蔽已知敏感模式；null 原样返回 */
    public static String mask(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String out = EMAIL.matcher(s).replaceAll(REDACTED);
        out = PHONE.matcher(out).replaceAll(REDACTED);
        out = TOKEN.matcher(out).replaceAll(REDACTED);
        return out;
    }

    /** 该文本是否含敏感标记（验收用扫描口径：判断是否泄露） */
    public static boolean containsSensitive(String s) {
        if (s == null) {
            return false;
        }
        return EMAIL.matcher(s).find() || PHONE.matcher(s).find() || TOKEN.matcher(s).find();
    }
}
