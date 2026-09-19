package com.ghy.mutiagent.repository.mapper;

import com.ghy.mutiagent.repository.entity.TraceDetail;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface TraceDetailMapper extends BaseMapper<TraceDetail> {

    /** S12 过期判定：ageDays > retentionDays */
    @Select("SELECT * FROM t_trace_detail WHERE age_days > #{retentionDays} ORDER BY id")
    List<TraceDetail> selectExpired(@Param("retentionDays") int retentionDays);

    @Delete("DELETE FROM t_trace_detail WHERE id=#{id}")
    int deleteOne(@Param("id") String id);
}
