package com.ghy.mutiagent.context.baseline;

/** 调用前的混合文本粗估；真实计费必须使用 Provider TokenUsage。 */
public final class ContextSizeEstimator {

    private ContextSizeEstimator() {
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(ch);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                cjk++;
            } else {
                other++;
            }
        }
        return (int) Math.ceil(cjk * 1.1d + other / 4.0d);
    }
}
