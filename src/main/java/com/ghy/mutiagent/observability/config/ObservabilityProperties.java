package com.ghy.mutiagent.observability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Observability 模块配置（开发文档 §7.3）：默认全部关闭，便于安全发布；
 * 缺 obs_ 表时应用也能正常启动（enabled=false 装配 noop）。
 */
@Component
@ConfigurationProperties(prefix = "observability")
public class ObservabilityProperties {

    /** 模块总开关：false 时 API 返回明确不可用语义，采集全 noop */
    private boolean enabled = false;

    private Capture capture = new Capture();
    private Buffer buffer = new Buffer();
    private Batch batch = new Batch();
    private Payload payload = new Payload();
    private Retention retention = new Retention();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Capture getCapture() {
        return capture;
    }

    public void setCapture(Capture capture) {
        this.capture = capture;
    }

    public Buffer getBuffer() {
        return buffer;
    }

    public void setBuffer(Buffer buffer) {
        this.buffer = buffer;
    }

    public Batch getBatch() {
        return batch;
    }

    public void setBatch(Batch batch) {
        this.batch = batch;
    }

    public Payload getPayload() {
        return payload;
    }

    public void setPayload(Payload payload) {
        this.payload = payload;
    }

    public Retention getRetention() {
        return retention;
    }

    public void setRetention(Retention retention) {
        this.retention = retention;
    }

    public static class Capture {
        /** 采集开关：enabled 且 capture.enabled 才装配真实记录器 */
        private boolean enabled = false;
        /** BUFFERED：有界缓冲 + 独立批写线程（第一版唯一模式） */
        private String mode = "BUFFERED";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }
    }

    public static class Buffer {
        /** 有界缓冲容量；满时丢明细记 gap（业务不受影响） */
        private int capacity = 10000;

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }
    }

    public static class Batch {
        /** 每批最多写入事件数 */
        private int size = 200;
        /** 批写轮询间隔（毫秒） */
        private long intervalMs = 1000;

        public int getSize() {
            return size;
        }

        public void setSize(int size) {
            this.size = size;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }
    }

    public static class Payload {
        /** 载荷上限（字节）；超出截断并标 TRUNCATED */
        private int maxBytes = 262144;

        public int getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(int maxBytes) {
            this.maxBytes = maxBytes;
        }
    }

    public static class Retention {
        /** obs_ 数据保留天数（仅影响观测数据，不触及业务表） */
        private int days = 30;

        public int getDays() {
            return days;
        }

        public void setDays(int days) {
            this.days = days;
        }
    }
}
