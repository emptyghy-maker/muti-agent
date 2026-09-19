package com.ghy.mutiagent.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S11 取消注册表：每操作一个令牌；cancel 置位对在途线程可见；forget 清理。
 */
class CancelRegistryTest {

    @Test
    void tokenIsPerOperationAndVisibleAcrossThreads() throws InterruptedException {
        CancelRegistry registry = new CancelRegistry();
        CancelRegistry.CancelToken a = registry.token("op-a");
        CancelRegistry.CancelToken b = registry.token("op-b");
        assertTrue(a == registry.token("op-a"), "同操作令牌必须复用同一实例");
        assertFalse(a == b);

        CountDownLatch sawCancel = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            while (!a.isCancelled()) {
                Thread.yield();
            }
            sawCancel.countDown();
        });
        worker.start();
        Thread.sleep(50);
        assertFalse(sawCancel.getCount() == 0);
        registry.cancel("op-a");
        assertTrue(sawCancel.await(2, TimeUnit.SECONDS), "取消信号必须对在途线程可见");
        assertTrue(a.isCancelled());
        assertFalse(b.isCancelled(), "取消一个操作不得影响其他操作");
        worker.join(2000);
    }

    @Test
    void cancelBeforeTokenCreatedTakesEffect() {
        CancelRegistry registry = new CancelRegistry();
        CancelRegistry.CancelToken t = registry.token("op-a");
        registry.cancel("op-a");
        assertTrue(t.isCancelled());
        CancelRegistry.CancelToken again = registry.token("op-a");
        assertTrue(again == t, "重复取令牌不得重建（否则取消信号丢失）");
    }

    @Test
    void forgetRemovesToken() {
        CancelRegistry registry = new CancelRegistry();
        CancelRegistry.CancelToken t = registry.token("op-a");
        registry.forget("op-a");
        assertFalse(t.isCancelled());
        CancelRegistry.CancelToken fresh = registry.token("op-a");
        assertFalse(fresh == t, "forget 后重新生成令牌");
    }
}
