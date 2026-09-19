package com.ghy.mutiagent.observability.api;

import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.common.Result;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.observability.application.ObsAuditService;
import com.ghy.mutiagent.observability.application.ObsTaskService;
import com.ghy.mutiagent.observability.application.ObsViews.AnnotationView;
import com.ghy.mutiagent.observability.application.ObsViews.RevisionView;
import com.ghy.mutiagent.observability.application.ObsViews.TaskView;
import com.ghy.mutiagent.observability.persistence.ObsChangeRevision;
import com.ghy.mutiagent.observability.persistence.ObsOptimizationTask;
import com.ghy.mutiagent.observability.security.ObsScope;
import com.ghy.mutiagent.security.AuthenticatedUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 优化任务/修订/备注接口（开发文档 §13）：管理写操作限 ADMIN；
 * 备注由可见该 run 的用户追加；全部动作入 obs_audit；不产生业务执行或 Git 修改。
 */
@RestController
@RequestMapping("/api/v1/observability")
public class ObservabilityTaskController {

    private final ObsAvailability availability;
    private final ObsTaskService taskService;
    private final ObsAuditService audit;

    public ObservabilityTaskController(ObsAvailability availability,
                                       ObsTaskService taskService, ObsAuditService audit) {
        this.availability = availability;
        this.taskService = taskService;
        this.audit = audit;
    }

    @GetMapping("/tasks")
    public Result<List<TaskView>> tasks() {
        availability.requireEnabled();
        AuthenticatedUser actor = availability.requireActor();
        return Result.ok(taskService.tasks(ObsScope.of(actor.id(), actor.role())));
    }

    @PostMapping("/tasks")
    public Result<TaskView> createTask(@RequestBody ObsOptimizationTask draft) {
        availability.requireEnabled();
        AuthenticatedUser actor = availability.requireActor();
        ObsScope scope = ObsScope.of(actor.id(), actor.role());
        TaskView task = taskService.createTask(scope, draft, actor);
        audit.record(actor, "CREATE_TASK", "TASK", String.valueOf(task.id()), draft.getTitle());
        return Result.ok(task);
    }

    @PostMapping("/tasks/{id}/revisions")
    public Result<RevisionView> addRevision(@PathVariable Long id,
                                            @RequestBody ObsChangeRevision draft) {
        availability.requireEnabled();
        AuthenticatedUser actor = availability.requireActor();
        ObsScope scope = ObsScope.of(actor.id(), actor.role());
        RevisionView revision = taskService.addRevision(scope, id, draft, actor);
        audit.record(actor, "ADD_REVISION", "TASK", String.valueOf(id),
                "revisionNo=" + revision.revisionNo());
        return Result.ok(revision);
    }

    @PostMapping("/tasks/{id}/links")
    public Result<Map<String, Object>> linkRun(@PathVariable Long id,
                                               @RequestBody Map<String, Object> body) {
        availability.requireEnabled();
        AuthenticatedUser actor = availability.requireActor();
        ObsScope scope = ObsScope.of(actor.id(), actor.role());
        String runId = String.valueOf(body.getOrDefault("runId", ""));
        String role = body.get("role") == null ? "RELATED" : String.valueOf(body.get("role"));
        taskService.linkRun(scope, id, runId, role, actor);
        audit.record(actor, "LINK_RUN", "TASK", String.valueOf(id), runId + "/" + role);
        return Result.ok(Map.of("linked", true));
    }

    @PostMapping("/runs/{id}/annotations")
    public Result<AnnotationView> annotate(@PathVariable String id,
                                           @RequestBody Map<String, Object> body) {
        availability.requireEnabled();
        AuthenticatedUser actor = availability.requireActor();
        ObsScope scope = ObsScope.of(actor.id(), actor.role());
        String content = body.get("content") == null ? null : String.valueOf(body.get("content"));
        AnnotationView annotation = taskService.annotate(scope, id, content, actor);
        audit.record(actor, "ANNOTATE_RUN", "RUN", id, null);
        return Result.ok(annotation);
    }
}
