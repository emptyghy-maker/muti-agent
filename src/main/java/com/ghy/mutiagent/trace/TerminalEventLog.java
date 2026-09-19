package com.ghy.mutiagent.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 终态事件日志（手册 §6）：operation 的终态事件按 eventId 幂等持久化，
 * 同 eventId 重复投递只保留首次；重启后从存储恢复，报表仍只有一个逻辑操作。
 * 时长取冻结值（TraceContext.finish 的 endNs），恢复前后一致。
 */
public final class TerminalEventLog {

    /** 终态事件记录：事件 ID + 逻辑操作 + 终态 + 冻结时长 + 负载 */
    public record TerminalEventRecord(String eventId, String operationId, String status,
                                      long durationMs, String payload) {
    }

    /** 持久化边界：append-only + 幂等插入 + 全量恢复（重启入口） */
    public interface TerminalEventStorage {
        boolean insertIfAbsent(TerminalEventRecord record);

        Optional<TerminalEventRecord> findByEventId(String eventId);

        List<TerminalEventRecord> loadAll();

        long count();
    }

    /** 内存实现（单测/无持久化需求场景） */
    public static final class InMemoryStorage implements TerminalEventStorage {
        private final Map<String, TerminalEventRecord> records = new ConcurrentHashMap<>();

        @Override
        public boolean insertIfAbsent(TerminalEventRecord record) {
            return records.putIfAbsent(record.eventId(), record) == null;
        }

        @Override
        public Optional<TerminalEventRecord> findByEventId(String eventId) {
            return Optional.ofNullable(records.get(eventId));
        }

        @Override
        public List<TerminalEventRecord> loadAll() {
            List<TerminalEventRecord> out = new ArrayList<>(records.values());
            out.sort((a, b) -> Long.compare(a.durationMs(), b.durationMs()));
            return List.copyOf(out);
        }

        @Override
        public long count() {
            return records.size();
        }
    }

    private final TerminalEventStorage storage;

    public TerminalEventLog(TerminalEventStorage storage) {
        if (storage == null) {
            throw new IllegalArgumentException("storage 不能为空");
        }
        this.storage = storage;
    }

    /** 幂等追加：返回是否为新事件（同 eventId 重复投递返回 false） */
    public boolean append(TerminalEventRecord record) {
        return storage.insertIfAbsent(record);
    }

    /** 重启恢复：从持久化存储全量重载（新进程实例只依赖存储，不依赖内存） */
    public List<TerminalEventRecord> reload() {
        return storage.loadAll();
    }

    public long persistedCount() {
        return storage.count();
    }

    public Optional<TerminalEventRecord> find(String eventId) {
        return storage.findByEventId(eventId);
    }
}
