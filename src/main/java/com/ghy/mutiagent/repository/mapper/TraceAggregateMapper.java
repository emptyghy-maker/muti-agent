package com.ghy.mutiagent.repository.mapper;

import com.ghy.mutiagent.repository.entity.TraceAggregate;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface TraceAggregateMapper extends BaseMapper<TraceAggregate> {

    @Delete("DELETE FROM t_trace_aggregate WHERE session_id=#{sessionId}")
    int deleteBySession(@Param("sessionId") String sessionId);
}
