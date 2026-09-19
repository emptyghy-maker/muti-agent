package com.ghy.mutiagent.observability.collection;

import com.ghy.mutiagent.trace.Redactor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 载荷净化（开发文档 §7.1）：Redactor 遮蔽 → 超限截断（TRUNCATED）→ 摘要哈希。
 * 同步工作只做轻量快照/脱敏摘要，不序列化完整会话；原文仅在存储层按状态落库。
 */
public final class PayloadSanitizer {

    public static final int DEFAULT_MAX_BYTES = 256 * 1024;

    /** 净化结果：内容 + 状态 + 大小（字节）+ 内容摘要 */
    public record Sanitized(String content, String status, int sizeBytes, String digest) {
    }

    private PayloadSanitizer() {
    }

    public static Sanitized sanitize(String raw, int maxBytes) {
        if (raw == null) {
            return new Sanitized(null, com.ghy.mutiagent.observability.domain.ObsStatuses.PAYLOAD_REDACTED,
                    0, sha256(""));
        }
        String masked = Redactor.mask(raw);
        boolean redacted = !masked.equals(raw);
        byte[] bytes = masked.getBytes(StandardCharsets.UTF_8);
        String status;
        String content;
        int size;
        if (bytes.length > maxBytes) {
            String cut = new String(bytes, 0, maxBytes, StandardCharsets.UTF_8);
            content = cut + "…[TRUNCATED]";
            status = com.ghy.mutiagent.observability.domain.ObsStatuses.PAYLOAD_TRUNCATED;
            size = bytes.length;
        } else {
            content = masked;
            status = redacted ? com.ghy.mutiagent.observability.domain.ObsStatuses.PAYLOAD_REDACTED
                    : com.ghy.mutiagent.observability.domain.ObsStatuses.PAYLOAD_FULL;
            size = bytes.length;
        }
        return new Sanitized(content, status, size, sha256(raw));
    }

    /** 摘要哈希：SHA-256 前 16 位十六进制 */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text == null ? new byte[0]
                    : text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
