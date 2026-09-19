package com.ghy.mutiagent.observability.api;

import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.common.Result;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.observability.application.ObsAuditService;
import com.ghy.mutiagent.observability.application.ObsPayloadQueryService;
import com.ghy.mutiagent.observability.application.ObsRunQueryService;
import com.ghy.mutiagent.observability.application.ObsViews.EventPage;
import com.ghy.mutiagent.observability.application.ObsViews.PayloadView;
import com.ghy.mutiagent.observability.application.ObsViews.RunDetail;
import com.ghy.mutiagent.observability.application.ObsViews.RunPage;
import com.ghy.mutiagent.observability.application.ObsViews.SpanView;
import com.ghy.mutiagent.observability.security.ObsScope;
import com.ghy.mutiagent.security.AuthenticatedUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运行/事件/载荷查询接口（开发文档 §10）：/api/v1/observability 命名空间。
 * 复用 Result 封装与既有身份；不改 TraceController/UsageController；无事件写入 API（防伪造）。
 * 非法 cursor 400；不存在/无权统一 404（不探测）；ADMIN 跨用户查询入 obs_audit。
 */
@RestController
@RequestMapping("/api/v1/observability")
public class ObservabilityRunsController {

    private final ObsAvailability availability;
    private final ObsRunQueryService runQuery;
    private final ObsPayloadQueryService payloadQuery;
    private final ObsAuditService audit;

    public ObservabilityRunsController(ObsAvailability availability,
                                       ObsRunQueryService runQuery,
                                       ObsPayloadQueryService payloadQuery,
                                       ObsAuditService audit) {
        this.availability = availability;
        this.runQuery = runQuery;
        this.payloadQuery = payloadQuery;
        this.audit = audit;
    }

    @GetMapping("/runs")
    public Result<RunPage> runs(@RequestParam(required = false) String cursor,
                                @RequestParam(defaultValue = "50") int limit,
                                @RequestParam(required = false) Long ownerId) {
        availability.requireEnabled();
        AuthenticatedUser actor = availability.requireActor();
        ObsScope scope = ObsScope.of(actor.id(), actor.role());
        if (ownerId != null && scope.admin() && !ownerId.equals(actor.id())) {
            audit.record(actor, "QUERY_RUNS", "USER", String.valueOf(ownerId), "跨用户运行列表");
            scope = new ObsScope(ownerId, true);
        } else if (ownerId != null && !ownerId.equals(actor.id())) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        try {
            return Result.ok(runQuery.listRuns(scope, cursor, limit));
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_ERROR.getCode(), e.getMessage());
        }
    }

    @GetMapping("/runs/{runId}")
    public Result<RunDetail> run(@PathVariable String runId) {
        availability.requireEnabled();
        AuthenticatedUser actor = availability.requireActor();
        ObsScope scope = ObsScope.of(actor.id(), actor.role());
        RunDetail detail = runQuery.detail(scope, runId);
        if (detail == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        return Result.ok(detail);
    }

    @GetMapping("/runs/{runId}/spans")
    public Result<List<SpanView>> spans(@PathVariable String runId,
                                        @RequestParam(defaultValue = "0") int offset,
                                        @RequestParam(defaultValue = "50") int limit) {
        availability.requireEnabled();
        AuthenticatedUser actor = availability.requireActor();
        ObsScope scope = ObsScope.of(actor.id(), actor.role());
        List<SpanView> spans = runQuery.spans(scope, runId, offset, limit);
        if (spans == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        return Result.ok(spans);
    }

    @GetMapping("/runs/{runId}/events")
    public Result<EventPage> events(@PathVariable String runId,
                                    @RequestParam(defaultValue = "0") long cursor,
                                    @RequestParam(defaultValue = "50") int limit) {
        availability.requireEnabled();
        AuthenticatedUser actor = availability.requireActor();
        ObsScope scope = ObsScope.of(actor.id(), actor.role());
        EventPage page = runQuery.events(scope, runId, cursor, limit);
        if (page == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        return Result.ok(page);
    }

    @GetMapping("/payloads/{payloadId}")
    public Result<PayloadView> payload(@PathVariable String payloadId) {
        availability.requireEnabled();
        AuthenticatedUser actor = availability.requireActor();
        ObsScope scope = ObsScope.of(actor.id(), actor.role());
        PayloadView payload = payloadQuery.payload(scope, payloadId);
        if (payload == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        return Result.ok(payload);
    }
}
