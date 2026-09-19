package com.ghy.mutiagent.observability.collection;

import com.ghy.mutiagent.observability.domain.ObsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 有界事件缓冲（开发文档 §7.1/§8）：满时丢弃明细并累计 gapCount（查询层显式暴露缺口），
 * 绝不阻塞业务线程；丢失的量与时刻可被报告为 GAP。
 */
public final class EventBuffer {

    private final int capacity;
    private final BlockingQueue<ObsEvent> queue;
    private final AtomicLong gapCount = new AtomicLong();

    public EventBuffer(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.queue = new ArrayBlockingQueue<>(this.capacity);
    }

    /** 入队：成功返回 true；缓冲满返回 false 并累计 gapCount（明细丢弃，业务不受影响） */
    public boolean offer(ObsEvent event) {
        if (event == null) {
            return false;
        }
        boolean ok = queue.offer(event);
        if (!ok) {
            gapCount.incrementAndGet();
        }
        return ok;
    }

    /** 批量取出（最多 max 条） */
    public List<ObsEvent> drain(int max) {
        List<ObsEvent> out = new ArrayList<>();
        queue.drainTo(out, Math.max(1, max));
        return out;
    }

    public long gapCount() {
        return gapCount.get();
    }

    /** 取出并清零缺口计数（随批次写入报告，避免重复上报） */
    public long takeGapCount() {
        return gapCount.getAndSet(0);
    }

    public int capacity() {
        return capacity;
    }

    public int size() {
        return queue.size();
    }
}
