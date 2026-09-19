package com.ghy.mutiagent.observability.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** obs_payload Mapper：保留期管理（只处理 obs_ 数据，内容过期置 NULL 摘要保留） */
public interface ObsPayloadRowMapper extends BaseMapper<ObsPayloadRow> {

    @Select("SELECT * FROM obs_payload WHERE expires_at IS NOT NULL AND expires_at < #{now} ORDER BY expires_at")
    List<ObsPayloadRow> selectExpired(@Param("now") LocalDateTime now);

    @Update("UPDATE obs_payload SET content = NULL, status = 'EXPIRED' WHERE payload_id = #{payloadId}")
    int expireContent(@Param("payloadId") String payloadId);
}
