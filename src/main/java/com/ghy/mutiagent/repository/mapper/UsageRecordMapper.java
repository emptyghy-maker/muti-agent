package com.ghy.mutiagent.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ghy.mutiagent.repository.entity.UsageRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface UsageRecordMapper extends BaseMapper<UsageRecord> {

    /** 会话聊天历史（Q&A 流水按时间正序）：恢复窗口回放用户/助手对话，超长截断 */
    @Select("SELECT question, answer FROM t_usage_record WHERE session_id=#{sessionId} "
            + "AND action='对话' AND question IS NOT NULL AND question<>'' "
            + "ORDER BY created_at ASC, id ASC LIMIT #{limit}")
    List<Map<String, Object>> selectQaHistory(@Param("sessionId") String sessionId,
                                              @Param("limit") int limit);
}
