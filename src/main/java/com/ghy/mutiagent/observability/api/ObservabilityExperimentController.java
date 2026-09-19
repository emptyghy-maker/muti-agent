package com.ghy.mutiagent.observability.api;

import com.ghy.mutiagent.common.Result;
import com.ghy.mutiagent.observability.application.ObsAuditService;
import com.ghy.mutiagent.observability.application.ObsExperimentService;
import com.ghy.mutiagent.observability.application.ObsViews.ComparisonView;
import com.ghy.mutiagent.observability.security.ObsScope;
import com.ghy.mutiagent.security.AuthenticatedUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 实验导入与对照接口（开发文档 §14）：导入限 ADMIN；不执行模型、不修改线上配置。
 */
@RestController
@RequestMapping("/api/v1/observability")
public class ObservabilityExperimentController {

    private final ObsAvailability availability;
    private final ObsExperimentService experimentService;
    private final ObsAuditService audit;

    public ObservabilityExperimentController(ObsAvailability availability,
                                             ObsExperimentService experimentService,
                                             ObsAuditService audit) {
        this.availability = availability;
        this.experimentService = experimentService;
        this.audit = audit;
    }

    @PostMapping("/experiments/imports")
    public Result<Map<String, Object>> imports(@RequestBody Map<String, Object> body) {
        availability.requireEnabled();
        AuthenticatedUser actor = availability.requireActor();
        ObsScope scope = ObsScope.of(actor.id(), actor.role());
        Map<String, Object> result = experimentService.importExperiment(scope, body, actor);
        audit.record(actor, "IMPORT_EXPERIMENT", "EXPERIMENT",
                String.valueOf(result.get("experimentId")), null);
        return Result.ok(result);
    }

    @GetMapping("/experiments/{id}/comparison")
    public Result<ComparisonView> comparison(@PathVariable Long id,
                                             @RequestParam(defaultValue = "30") int requiredPairs) {
        availability.requireEnabled();
        AuthenticatedUser actor = availability.requireActor();
        ObsScope scope = ObsScope.of(actor.id(), actor.role());
        ComparisonView view = experimentService.comparison(scope, id, requiredPairs);
        audit.record(actor, "COMPARE_EXPERIMENT", "EXPERIMENT", String.valueOf(id), null);
        return Result.ok(view);
    }
}
