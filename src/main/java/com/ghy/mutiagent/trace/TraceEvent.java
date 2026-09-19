package com.ghy.mutiagent.trace;

/**
 * S12 链路事件：终态事件在第一次 finish 时原子追加一次，
 * 重复 finish 不得新增重复终态事件（P2_01_01）。
 */
public record TraceEvent(String type, String status, long atNs) {

    public static TraceEvent terminal(String status, long atNs) {
        return new TraceEvent("TERMINAL", status, atNs);
    }
}
