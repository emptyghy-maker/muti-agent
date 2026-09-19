package com.ghy.mutiagent.repository.mapper;

import com.ghy.mutiagent.repository.entity.ItineraryFeedback;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ItineraryFeedbackMapper extends BaseMapper<ItineraryFeedback> {

    /** S12：幂等回查（同 owner + feedbackKey 唯一） */
    @Select("SELECT * FROM t_itinerary_feedback WHERE user_id=#{userId} AND feedback_key=#{feedbackKey}")
    ItineraryFeedback findByKey(@Param("userId") Long userId, @Param("feedbackKey") String feedbackKey);
}
