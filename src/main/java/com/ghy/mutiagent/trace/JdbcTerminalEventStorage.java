package com.ghy.mutiagent.trace;

import com.ghy.mutiagent.repository.entity.TerminalEvent;
import com.ghy.mutiagent.repository.mapper.TerminalEventMapper;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 终态事件 JDBC 持久化：eventId 主键 + 先查后插 + 冲突兜底，
 * 保证同 eventId 并发/重复投递只保留一条；重启后 loadAll 全量恢复。
 */
public final class JdbcTerminalEventStorage implements TerminalEventLog.TerminalEventStorage {

    private final TerminalEventMapper mapper;

    public JdbcTerminalEventStorage(TerminalEventMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean insertIfAbsent(TerminalEventLog.TerminalEventRecord record) {
        if (mapper.selectByEventId(record.eventId()) != null) {
            return false;
        }
        TerminalEvent row = new TerminalEvent();
        row.setEventId(record.eventId());
        row.setOperationId(record.operationId());
        row.setStatus(record.status());
        row.setDurationMs(record.durationMs());
        row.setPayload(record.payload());
        row.setCreatedAt(LocalDateTime.now());
        try {
            mapper.insert(row);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public Optional<TerminalEventLog.TerminalEventRecord> findByEventId(String eventId) {
        return Optional.ofNullable(mapper.selectByEventId(eventId)).map(this::toRecord);
    }

    @Override
    public List<TerminalEventLog.TerminalEventRecord> loadAll() {
        List<TerminalEventLog.TerminalEventRecord> out = new ArrayList<>();
        for (TerminalEvent row : mapper.selectList(null)) {
            out.add(toRecord(row));
        }
        out.sort((a, b) -> a.eventId().compareTo(b.eventId()));
        return List.copyOf(out);
    }

    @Override
    public long count() {
        return mapper.selectCount(null);
    }

    private TerminalEventLog.TerminalEventRecord toRecord(TerminalEvent row) {
        return new TerminalEventLog.TerminalEventRecord(
                row.getEventId(), row.getOperationId(), row.getStatus(),
                row.getDurationMs() == null ? 0L : row.getDurationMs(), row.getPayload());
    }
}
