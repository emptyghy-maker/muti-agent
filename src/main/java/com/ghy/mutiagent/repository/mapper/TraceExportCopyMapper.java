package com.ghy.mutiagent.repository.mapper;

import com.ghy.mutiagent.repository.entity.TraceExportCopy;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface TraceExportCopyMapper extends BaseMapper<TraceExportCopy> {

    @Delete("DELETE FROM t_trace_export WHERE detail_id=#{detailId}")
    int deleteByDetail(@Param("detailId") String detailId);
}
