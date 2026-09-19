package com.ghy.mutiagent.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ghy.mutiagent.repository.entity.TerminalEvent;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 终态事件 Mapper：eventId 幂等查询（配合主键插入冲突做去重） */
public interface TerminalEventMapper extends BaseMapper<TerminalEvent> {

    @Select("SELECT * FROM t_terminal_event WHERE event_id=#{eventId}")
    TerminalEvent selectByEventId(@Param("eventId") String eventId);
}
