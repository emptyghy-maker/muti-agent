package com.ghy.mutiagent.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.repository.entity.Itinerary;
import com.ghy.mutiagent.repository.mapper.ItineraryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;

/**
 * 行程提交服务（S06-A）：独立 Bean 的短事务，与模型/规则计算（Planning）分离。
 *
 * - 模型调用、结构验证发生在事务外（事务只覆盖数据库写）；
 * - 失败必须向外传播：insert 受影响行数不是 1、主键为空、条件归档影响 0 行都判失败并整体回滚；
 * - rollbackFor = Exception.class：受检异常（如 mapper 声明的 SQLException）同样回滚，不在事务里吞异常。
 */
@Service
public class ItineraryCommitService {

    private static final Logger log = LoggerFactory.getLogger(ItineraryCommitService.class);

    /** 新行程提交命令 */
    public record CommitNew(Itinerary row) {
    }

    /** 调整提交命令：归档旧版本并落新版本（新行已由调用方在事务外构造） */
    public record CommitAdjustment(Long oldId, int expectedVersion, Itinerary newRow) {
    }

    public record CommitResult(Long newId, int newVersion) {
    }

    private final ItineraryMapper itineraryMapper;

    public ItineraryCommitService(ItineraryMapper itineraryMapper) {
        this.itineraryMapper = itineraryMapper;
    }

    /** 提交新行程（短事务）：insert 成功且生成主键才算成功 */
    @Transactional(rollbackFor = Exception.class)
    public CommitResult commitNew(CommitNew cmd) {
        Itinerary row = cmd.row();
        int rows = itineraryMapper.insert(row);
        if (rows != 1 || row.getId() == null) {
            log.error("[Itinerary] 行程落库失败：insert 影响 {} 行、主键={}", rows, row.getId());
            throw new BizException(ResultCode.EXECUTE_ERROR);
        }
        return new CommitResult(row.getId(), row.getVersion() == null ? 1 : row.getVersion());
    }

    /**
     * 提交调整版本（短事务）：重新核验旧版本 → 插入新行 → 版本号/父级回写 → 条件归档旧行。
     * 归档影响 0 行即版本冲突，整体回滚（新行一并撤销）。
     */
    @Transactional(rollbackFor = Exception.class)
    public CommitResult commitAdjustment(CommitAdjustment cmd) throws SQLException {
        Itinerary old = itineraryMapper.selectById(cmd.oldId());
        if (old == null || old.getUserId() == null || !old.getUserId().equals(cmd.newRow().getUserId())) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        if (!"ACTIVE".equals(old.getStatus()) || old.getVersion() == null
                || old.getVersion() != cmd.expectedVersion()) {
            throw new BizException(ResultCode.STATE_CONFLICT);
        }
        Itinerary neu = cmd.newRow();
        int ins = itineraryMapper.insert(neu);
        if (ins != 1 || neu.getId() == null) {
            throw new BizException(ResultCode.EXECUTE_ERROR);
        }
        int newVersion = old.getVersion() + 1;
        neu.setVersion(newVersion);
        neu.setParentId(old.getId());
        itineraryMapper.updateById(neu);

        int archived = itineraryMapper.archiveIfActive(old.getId(), old.getUserId(), old.getVersion());
        if (archived != 1) {
            log.warn("[Itinerary] 调整提交版本冲突：旧行 {} 归档影响 {} 行，整体回滚", old.getId(), archived);
            throw new BizException(ResultCode.STATE_CONFLICT);
        }
        return new CommitResult(neu.getId(), newVersion);
    }
}
