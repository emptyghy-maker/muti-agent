package com.ghy.mutiagent.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 同步生成进行中注册表（进程内，不落库）：
 * 酒店确认触发的同步生成期间标记会话「生成中」，重复确认/恢复场景据此拒绝二次生成。
 *
 * 语义边界：生成线程与注册表同属一个进程——进程崩溃/重启后标记自然消失，
 * 此时旧生成已消亡、重新生成是必要且唯一的路径（不存在重复浪费）。
 * 单实例部署语义；多实例下仅保证同实例内的防重。
 */
@Component
public class GenerationRegistry {

    private final Map<String, Long> generatingUntil = new ConcurrentHashMap<>();

    /** 尝试占用（带租约）：成功返回 true；已有未过期占用返回 false（重复确认应拒绝） */
    public boolean begin(String sessionId, long leaseMs) {
        if (sessionId == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long prev = generatingUntil.putIfAbsent(sessionId, now + leaseMs);
        if (prev == null) {
            return true;
        }
        if (prev < now) {
            return generatingUntil.replace(sessionId, prev, now + leaseMs);
        }
        return false;
    }

    /** 生成结束（成功/失败都调用）释放标记 */
    public void end(String sessionId) {
        if (sessionId != null) {
            generatingUntil.remove(sessionId);
        }
    }

    /** 是否有未过期的生成占用 */
    public boolean isGenerating(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        Long until = generatingUntil.get(sessionId);
        return until != null && until >= System.currentTimeMillis();
    }
}
