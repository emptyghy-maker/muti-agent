package com.ghy.mutiagent.observability.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** obs_event Mapper：eventId 幂等插入 + 增量游标查询 */
public interface ObsEventRowMapper extends BaseMapper<ObsEventRow> {

    @Insert("INSERT IGNORE INTO obs_event (event_id, run_id, operation_id, span_id, parent_span_id,"
            + " node_execution_id, event_type, occurred_at, received_at, owner_id, schema_version,"
            + " producer_id, sequence_no, digest, payload_ref, summary_json, created_at) VALUES ("
            + "#{e.eventId}, #{e.runId}, #{e.operationId}, #{e.spanId}, #{e.parentSpanId},"
            + " #{e.nodeExecutionId}, #{e.eventType}, #{e.occurredAt}, #{e.receivedAt}, #{e.ownerId},"
            + " #{e.schemaVersion}, #{e.producerId}, #{e.sequenceNo}, #{e.digest}, #{e.payloadRef},"
            + " #{e.summaryJson}, NOW(3))")
    int insertIgnore(@Param("e") ObsEventRow row);

    /** 增量事件：sequence_no > cursor（播放顺序，不声称严格因果），限制条数 */
    @Select("SELECT * FROM obs_event WHERE run_id=#{runId} AND sequence_no > #{cursor}"
            + " ORDER BY sequence_no ASC LIMIT #{limit}")
    List<ObsEventRow> selectAfterSequence(@Param("runId") String runId,
                                          @Param("cursor") long cursor,
                                          @Param("limit") int limit);
}
