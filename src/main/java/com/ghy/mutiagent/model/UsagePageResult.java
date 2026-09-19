package com.ghy.mutiagent.model;

import com.ghy.mutiagent.repository.entity.UsageRecord;
import lombok.Data;

import java.util.List;

/** 用量明细分页结果 */
@Data
public class UsagePageResult {
    private long total;
    private List<UsageRecord> list = List.of();
}
