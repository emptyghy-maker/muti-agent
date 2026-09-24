package com.ghy.mutiagent.context.turn;

/** 保守标准化：统一字符形式和空白，不删除否定、数量单位、范围词和地点原文。 */
public final class TurnTextNormalizer {

    public String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch >= '０' && ch <= '９') {
                out.append((char) ('0' + ch - '０'));
            } else if (ch == '，') {
                out.append(',');
            } else if (ch == '；') {
                out.append(';');
            } else if (ch == '：') {
                out.append(':');
            } else if (ch == '（') {
                out.append('(');
            } else if (ch == '）') {
                out.append(')');
            } else {
                out.append(ch);
            }
        }
        String normalized = out.toString().trim().replaceAll("\\s+", " ");
        // 只转换明确计量词，避免把地点名、店名中的中文数字误改写。
        normalized = normalized
                .replaceAll("一\\s*天", "1天")
                .replaceAll("两\\s*天", "2天")
                .replaceAll("二\\s*天", "2天")
                .replaceAll("三\\s*天", "3天")
                .replaceAll("四\\s*天", "4天")
                .replaceAll("五\\s*天", "5天")
                .replaceAll("一\\s*(?:个)?人", "1人")
                .replaceAll("(?:两|俩|二)\\s*(?:个)?人", "2人")
                .replaceAll("三\\s*(?:个)?人", "3人")
                .replaceAll("四\\s*(?:个)?人", "4人")
                .replaceAll("五\\s*(?:个)?人", "5人");
        return normalized;
    }
}
