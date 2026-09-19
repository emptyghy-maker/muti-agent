package com.ghy.mutiagent.controller;

import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.common.Result;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.security.AuthenticatedUser;
import com.ghy.mutiagent.trace.TraceService;
import com.ghy.mutiagent.trace.TraceView;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * trace 查询接口：查看最近的 Agent 调用链路（耗时 + token）。
 * S12：所有查询按当前登录用户过滤（普通用户只能看自己的链路）；
 * 返回一律为脱敏视图，不暴露原始 prompt/response。
 */
@RestController
@RequestMapping("/api/v1/trace")
public class TraceController {

    private final TraceService traceService;

    public TraceController(TraceService traceService) {
        this.traceService = traceService;
    }

    /** 最近 limit 条问数链路（仅本用户），默认 20，上限 100 */
    @GetMapping("/recent")
    public Result<List<TraceView>> recent(@RequestParam(defaultValue = "20") int limit) {
        return Result.ok(traceService.viewsFor(requireActor().id(), Math.min(limit, 100)));
    }

    /** 指定会话链路详情（仅本用户；无权访问 403） */
    @GetMapping("/detail")
    public Result<TraceView> detail(@RequestParam("sessionId") String sessionId) {
        return Result.ok(TraceView.of(traceService.detail(requireActor().id(), sessionId)));
    }

    /** 受控导出（仅本用户，同一脱敏口径） */
    @GetMapping("/export")
    public Result<List<TraceView>> export(@RequestParam(defaultValue = "100") int limit) {
        return Result.ok(traceService.exportFor(requireActor().id(), Math.min(limit, 100)));
    }

    private AuthenticatedUser requireActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser)) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        return (AuthenticatedUser) auth.getPrincipal();
    }
}
