package com.ghy.mutiagent.observability.api;

import com.ghy.mutiagent.common.Result;
import com.ghy.mutiagent.observability.application.ObsExportService;
import com.ghy.mutiagent.observability.security.ObsScope;
import com.ghy.mutiagent.security.AuthenticatedUser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 受控脱敏导出接口（开发文档 §15）：ADMIN；先预览再执行，动作入 obs_audit；
 * 导出内容为摘要口径（无原文）；下载校验在导出时完成。
 */
@RestController
@RequestMapping("/api/v1/observability")
public class ObservabilityExportController {

    private final ObsAvailability availability;
    private final ObsExportService exportService;

    public ObservabilityExportController(ObsAvailability availability,
                                         ObsExportService exportService) {
        this.availability = availability;
        this.exportService = exportService;
    }

    @PostMapping("/exports/preview")
    public Result<Map<String, Object>> preview(@RequestParam(defaultValue = "100") int limit) {
        availability.requireEnabled();
        AuthenticatedUser actor = availability.requireActor();
        ObsScope scope = ObsScope.of(actor.id(), actor.role());
        return Result.ok(exportService.preview(scope, limit));
    }

    @PostMapping("/exports")
    public Result<Map<String, Object>> export(@RequestParam(defaultValue = "json") String format,
                                              @RequestParam(defaultValue = "100") int limit) {
        availability.requireEnabled();
        AuthenticatedUser actor = availability.requireActor();
        ObsScope scope = ObsScope.of(actor.id(), actor.role());
        Map<String, Object> data = exportService.export(scope, format, limit);
        exportService.auditExport(actor, format, ((Number) data.get("itemCount")).intValue());
        return Result.ok(data);
    }
}
