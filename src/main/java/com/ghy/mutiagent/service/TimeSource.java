package com.ghy.mutiagent.service;

/**
 * S08 可注入单调时钟：deadline 判断必须用单调时钟衡量进程内耗时，
 * 测试（门禁桥接）注入可控时钟，避免依赖 Thread.sleep 的偶然顺序。
 */
public interface TimeSource {

    TimeSource SYSTEM = System::nanoTime;

    /** 单调纳秒时间戳，仅允许与同一 TimeSource 的其他读数比较 */
    long nanoTime();
}
