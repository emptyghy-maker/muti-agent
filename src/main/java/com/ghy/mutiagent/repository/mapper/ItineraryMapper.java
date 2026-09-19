package com.ghy.mutiagent.repository.mapper;

import com.ghy.mutiagent.repository.entity.Itinerary;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.sql.SQLException;

public interface ItineraryMapper extends BaseMapper<Itinerary> {

    /**
     * 条件归档（S06-A 提交边界）：只有 owner/version/status 同时匹配才归档，影响 0 行即版本冲突。
     * 声明受检 SQLException：真实存储故障不经包装直接向上传播，由提交事务 rollbackFor=Exception 回滚。
     */
    @Update("UPDATE t_itinerary SET status='ARCHIVED' "
            + "WHERE id=#{id} AND user_id=#{userId} AND version=#{version} AND status='ACTIVE'")
    int archiveIfActive(@Param("id") Long id, @Param("userId") Long userId,
                        @Param("version") Integer version) throws SQLException;
}
