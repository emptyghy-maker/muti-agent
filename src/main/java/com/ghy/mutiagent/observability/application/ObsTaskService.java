package com.ghy.mutiagent.observability.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.observability.application.ObsViews.AnnotationView;
import com.ghy.mutiagent.observability.application.ObsViews.RevisionView;
import com.ghy.mutiagent.observability.application.ObsViews.TaskView;
import com.ghy.mutiagent.observability.persistence.ObsAnnotation;
import com.ghy.mutiagent.observability.persistence.ObsAnnotationMapper;
import com.ghy.mutiagent.observability.persistence.ObsChangeRevision;
import com.ghy.mutiagent.observability.persistence.ObsChangeRevisionMapper;
import com.ghy.mutiagent.observability.persistence.ObsOptimizationTask;
import com.ghy.mutiagent.observability.persistence.ObsOptimizationTaskMapper;
import com.ghy.mutiagent.observability.persistence.ObsRun;
import com.ghy.mutiagent.observability.persistence.ObsRunLink;
import com.ghy.mutiagent.observability.persistence.ObsRunLinkMapper;
import com.ghy.mutiagent.observability.persistence.ObsRunMapper;
import com.ghy.mutiagent.observability.security.ObsScope;
import com.ghy.mutiagent.security.AuthenticatedUser;
import org.springframework.dao.DuplicateKeyException;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 优化任务/修订/备注/关联（开发文档 §13）：管理写操作限 ADMIN（第一版）；
 * 修订追加不覆盖历史（task_id+revision_no 唯一，并发冲突 409）；
 * 备注追加保留历史，不覆盖 tool 输入输出；任务原文与旅行用户输入分开；
 * 不执行 git commit/push、不读取客户端任意本地路径。
 */
public final class ObsTaskService {

    private final ObsOptimizationTaskMapper taskMapper;
    private final ObsChangeRevisionMapper revisionMapper;
    private final ObsRunLinkMapper linkMapper;
    private final ObsAnnotationMapper annotationMapper;
    private final ObsRunMapper runMapper;

    public ObsTaskService(ObsOptimizationTaskMapper taskMapper,
                          ObsChangeRevisionMapper revisionMapper,
                          ObsRunLinkMapper linkMapper,
                          ObsAnnotationMapper annotationMapper,
                          ObsRunMapper runMapper) {
        this.taskMapper = taskMapper;
        this.revisionMapper = revisionMapper;
        this.linkMapper = linkMapper;
        this.annotationMapper = annotationMapper;
        this.runMapper = runMapper;
    }

    public List<TaskView> tasks(ObsScope scope) {
        requireManage(scope);
        List<TaskView> out = new ArrayList<>();
        for (ObsOptimizationTask t : taskMapper.selectList(
                new LambdaQueryWrapper<ObsOptimizationTask>()
                        .orderByDesc(ObsOptimizationTask::getId).last("LIMIT 100"))) {
            out.add(taskView(t));
        }
        return out;
    }

    public TaskView createTask(ObsScope scope, ObsOptimizationTask draft, AuthenticatedUser actor) {
        requireManage(scope);
        if (draft == null || draft.getTitle() == null || draft.getTitle().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR);
        }
        draft.setVersion(1);
        draft.setStatus(draft.getStatus() == null ? "OPEN" : draft.getStatus());
        draft.setCreatedBy(actor.id());
        taskMapper.insert(draft);
        return taskView(taskMapper.selectById(draft.getId()));
    }

    /** 新增修订：revisionNo 在任务内递增；并发同号冲突 → 409 */
    public RevisionView addRevision(ObsScope scope, Long taskId, ObsChangeRevision draft,
                                    AuthenticatedUser actor) {
        requireManage(scope);
        ObsOptimizationTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        List<ObsChangeRevision> existing = revisionMapper.selectList(
                new LambdaQueryWrapper<ObsChangeRevision>()
                        .eq(ObsChangeRevision::getTaskId, taskId)
                        .orderByDesc(ObsChangeRevision::getRevisionNo).last("LIMIT 1"));
        int next = existing.isEmpty() ? 1 : existing.get(0).getRevisionNo() + 1;
        draft.setTaskId(taskId);
        draft.setRevisionNo(next);
        draft.setCreatedBy(actor.id());
        draft.setStatus(draft.getStatus() == null ? "PROPOSED" : draft.getStatus());
        try {
            revisionMapper.insert(draft);
        } catch (DuplicateKeyException e) {
            throw new BizException(ResultCode.STATE_CONFLICT);
        }
        ObsOptimizationTask bump = new ObsOptimizationTask();
        bump.setVersion(task.getVersion() + 1);
        int bumped = taskMapper.update(bump,
                new LambdaQueryWrapper<ObsOptimizationTask>()
                        .eq(ObsOptimizationTask::getId, taskId)
                        .eq(ObsOptimizationTask::getVersion, task.getVersion()));
        if (bumped != 1) {
            throw new BizException(ResultCode.STATE_CONFLICT);
        }
        return revisionView(revisionMapper.selectById(draft.getId()));
    }

    public void linkRun(ObsScope scope, Long taskId, String runId, String role,
                        AuthenticatedUser actor) {
        requireManage(scope);
        if (taskMapper.selectById(taskId) == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        ObsRun run = runMapper.selectById(runId);
        if (run == null || run.getOwnerId() == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        ObsRunLink link = new ObsRunLink();
        link.setTaskId(taskId);
        link.setRunId(runId);
        link.setRole(role == null ? "RELATED" : role);
        try {
            linkMapper.insert(link);
        } catch (DuplicateKeyException e) {
            throw new BizException(ResultCode.STATE_CONFLICT);
        }
    }

    public AnnotationView annotate(ObsScope scope, String runId, String content,
                                   AuthenticatedUser actor) {
        ObsRun run = runMapper.selectById(runId);
        if (run == null || !scope.canSee(run.getOwnerId())) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        if (content == null || content.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR);
        }
        ObsAnnotation a = new ObsAnnotation();
        a.setRunId(runId);
        a.setAuthorId(actor.id());
        a.setAuthorUsername(actor.username());
        a.setContent(content);
        annotationMapper.insert(a);
        return new AnnotationView(a.getId(), a.getAuthorId(), a.getAuthorUsername(),
                a.getContent(), millis(a.getCreatedAt()));
    }

    private void requireManage(ObsScope scope) {
        if (scope == null || !scope.canManage()) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
    }

    private static TaskView taskView(ObsOptimizationTask t) {
        return new TaskView(t.getId(), t.getTitle(), t.getStatus(), t.getVersion(),
                t.getCreatedBy(), millis(t.getCreatedAt()), millis(t.getUpdatedAt()));
    }

    private static RevisionView revisionView(ObsChangeRevision r) {
        return new RevisionView(r.getId(), r.getRevisionNo(), r.getPromptVersion(),
                r.getModelVersion(), r.getGitHash(), r.getChanges(), r.getRationale(),
                r.getStatus(), r.getCreatedBy(), millis(r.getCreatedAt()));
    }

    private static Long millis(java.time.LocalDateTime t) {
        return t == null ? null
                : t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
