package com.ghy.mutiagent.trace;

import com.ghy.mutiagent.repository.entity.TraceDetail;
import com.ghy.mutiagent.repository.mapper.TraceDetailMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * S12 保留期清理作业：ageDays > retentionDays 的明细（含已知导出副本）删除，
 * 保留安全计数聚合。可重复运行、按明细逐条执行，准确报告删除范围；
 * 不执行未限定 owner/run 的全表删除。
 */
@Service
public class TraceRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(TraceRetentionJob.class);

    private final TraceDetailMapper detailMapper;
    private final TraceDetailStore store;

    public TraceRetentionJob(TraceDetailMapper detailMapper, TraceDetailStore store) {
        this.detailMapper = detailMapper;
        this.store = store;
    }

    /** 执行一轮清理，返回删除的明细 id（报告删除范围） */
    public List<String> purge(int retentionDays) {
        List<TraceDetail> expired = detailMapper.selectExpired(retentionDays);
        List<String> deleted = new ArrayList<>();
        for (TraceDetail detail : expired) {
            store.deleteExportCopy(detail.getId());
            store.deleteDetail(detail.getId());
            deleted.add(detail.getId());
        }
        log.info("[Trace] 保留期清理完成：保留 {} 天，删除明细 {} 条", retentionDays, deleted.size());
        return deleted;
    }
}
