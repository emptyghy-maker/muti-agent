package com.ghy.mutiagent.trace;

import com.ghy.mutiagent.repository.entity.TraceAggregate;
import com.ghy.mutiagent.repository.entity.TraceDetail;
import com.ghy.mutiagent.repository.entity.TraceExportCopy;
import com.ghy.mutiagent.repository.mapper.TraceAggregateMapper;
import com.ghy.mutiagent.repository.mapper.TraceDetailMapper;
import com.ghy.mutiagent.repository.mapper.TraceExportCopyMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * S12 追踪明细存储：明细（含原文）与安全聚合分离。
 * 普通观测写入失败不得触发业务重跑；本存储与 P0 operation 账本互不隶属。
 */
@Service
public class TraceDetailStore {

    private final TraceDetailMapper detailMapper;
    private final TraceExportCopyMapper exportMapper;
    private final TraceAggregateMapper aggregateMapper;

    public TraceDetailStore(TraceDetailMapper detailMapper,
                            TraceExportCopyMapper exportMapper,
                            TraceAggregateMapper aggregateMapper) {
        this.detailMapper = detailMapper;
        this.exportMapper = exportMapper;
        this.aggregateMapper = aggregateMapper;
    }

    public void saveDetail(TraceDetail detail) {
        detailMapper.insert(detail);
    }

    public void saveExportCopy(TraceExportCopy copy) {
        exportMapper.insert(copy);
    }

    public List<TraceDetail> details() {
        return detailMapper.selectList(null);
    }

    public List<TraceExportCopy> exportCopies() {
        return exportMapper.selectList(null);
    }

    public List<TraceAggregate> aggregates() {
        return aggregateMapper.selectList(null);
    }

    public void saveAggregate(TraceAggregate aggregate) {
        aggregateMapper.deleteBySession(aggregate.getSessionId());
        aggregateMapper.insert(aggregate);
    }

    public void deleteExportCopy(String detailId) {
        exportMapper.deleteByDetail(detailId);
    }

    public void deleteDetail(String id) {
        detailMapper.deleteOne(id);
    }
}
