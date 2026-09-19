package com.ghy.mutiagent.observability.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/** obs_usage_attempt Mapper：attemptKey 幂等插入（重复投递不新增计费行） */
public interface ObsUsageAttemptMapper extends BaseMapper<ObsUsageAttempt> {

    @Insert("INSERT IGNORE INTO obs_usage_attempt (attempt_key, run_id, span_id, source_system, agent,"
            + " model, input_tokens, output_tokens, usage_status, duration_ms, cost_amount, currency,"
            + " amount_status, price_snapshot, recorded_at) VALUES ("
            + "#{a.attemptKey}, #{a.runId}, #{a.spanId}, #{a.sourceSystem}, #{a.agent},"
            + " #{a.model}, #{a.inputTokens}, #{a.outputTokens}, #{a.usageStatus}, #{a.durationMs},"
            + " #{a.costAmount}, #{a.currency}, #{a.amountStatus}, #{a.priceSnapshot}, NOW(3))")
    int insertIgnore(@Param("a") ObsUsageAttempt attempt);
}
