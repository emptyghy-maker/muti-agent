package com.ghy.mutiagent.controller;

import com.ghy.mutiagent.common.Result;
import com.ghy.mutiagent.model.SessionAuditPage;
import com.ghy.mutiagent.model.UsageAdminSummary;
import com.ghy.mutiagent.model.UsageAlertRow;
import com.ghy.mutiagent.model.UsageFunnelStep;
import com.ghy.mutiagent.model.UsagePageResult;
import com.ghy.mutiagent.model.UsageSummary;
import com.ghy.mutiagent.model.UsageTrendPoint;
import com.ghy.mutiagent.model.UsageUserSummary;
import com.ghy.mutiagent.repository.entity.UsageRecord;
import com.ghy.mutiagent.security.AuthenticatedUser;
import com.ghy.mutiagent.service.UsageService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 用量记录查询接口（成本核算 + 审计）：
 * - 普通用户只能看自己的记录；ADMIN 角色看全部；
 * - /admin/** 由 SecurityConfig 强制 ROLE_ADMIN（非管理员 403）。
 */
@RestController
@RequestMapping("/api/v1/usage")
public class UsageController {

    private final UsageService usageService;

    public UsageController(UsageService usageService) {
        this.usageService = usageService;
    }

    /** 最近操作明细（本人/管理员全部） */
    @GetMapping("/records")
    public Result<List<UsageRecord>> records(@RequestParam(defaultValue = "50") int limit) {
        AuthenticatedUser u = currentUser();
        return Result.ok(usageService.recent(u == null ? null : u.id(), isAdmin(u), limit));
    }

    /** 按阶段汇总 token 消耗与调用次数（本人/管理员全部） */
    @GetMapping("/summary")
    public Result<List<UsageSummary>> summary() {
        AuthenticatedUser u = currentUser();
        return Result.ok(usageService.summary(u == null ? null : u.id(), isAdmin(u)));
    }

    // ==================== 管理员审计接口 ====================

    /** 用户维度汇总列表（筛选用） */
    @GetMapping("/admin/users")
    public Result<List<UsageUserSummary>> adminUsers() {
        return Result.ok(usageService.adminSummary().getByUser());
    }

    /** 多维汇总：按用户 / 模型 / 阶段 / 渠道 */
    @GetMapping("/admin/summary")
    public Result<UsageAdminSummary> adminSummary() {
        return Result.ok(usageService.adminSummary());
    }

    /** Q&A 审计明细分页（可按用户/阶段/渠道/日期过滤） */
    @GetMapping("/admin/records")
    public Result<UsagePageResult> adminRecords(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return Result.ok(usageService.page(userId, stage, channel,
                parseDate(dateFrom), parseDate(dateTo), limit, offset));
    }

    /** 会话维度审计分页（按会话聚合：一次规划一行；支持用户名/会话ID/最后阶段/结果过滤） */
    @GetMapping("/admin/sessions")
    public Result<SessionAuditPage> adminSessions(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String lastStage,
            @RequestParam(required = false) String resultStatus,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return Result.ok(usageService.sessionPage(userId, username, sessionId, lastStage, resultStatus,
                parseDate(dateFrom), parseDate(dateTo), limit, offset));
    }

    /** 单个会话的完整审计明细（阶段/动作/模型/token/耗时/返回内容，按时间正序） */
    @GetMapping("/admin/sessions/{sessionId}/records")
    public Result<List<UsageRecord>> adminSessionRecords(@PathVariable("sessionId") String sessionId) {
        return Result.ok(usageService.sessionRecords(sessionId));
    }

    /** 按天用量趋势（默认近 14 天） */
    @GetMapping("/admin/trend")
    public Result<List<UsageTrendPoint>> adminTrend(@RequestParam(defaultValue = "14") int days) {
        return Result.ok(usageService.trend(days));
    }

    /** 慢调用排行（双阈值：单次调用 ≥ callSec 秒、会话总耗时 ≥ sessionTotalSec 秒，0 为不过滤；
     *  showResolved=true 时展示已标记解决的调用并回填操作人/说明，默认只展示未解决） */
    @GetMapping("/admin/slow")
    public Result<List<UsageRecord>> adminSlow(
            @RequestParam(defaultValue = "0") int sessionTotalSec,
            @RequestParam(defaultValue = "60") int callSec,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "false") boolean showResolved) {
        return Result.ok(usageService.slow(sessionTotalSec, callSec, limit, showResolved));
    }

    /** 标记慢调用为已解决（可附解决说明；幂等） */
    @PostMapping("/admin/slow/{recordId}/resolve")
    public Result<Void> adminSlowResolve(@PathVariable("recordId") long recordId,
                                         @RequestParam(defaultValue = "") String note) {
        AuthenticatedUser u = currentUser();
        usageService.resolveSlow(recordId, u == null ? null : u.username(), note);
        return Result.ok(null);
    }

    /** 撤销已解决标记（恢复展示） */
    @PostMapping("/admin/slow/{recordId}/reopen")
    public Result<Void> adminSlowReopen(@PathVariable("recordId") long recordId) {
        usageService.reopenSlow(recordId);
        return Result.ok(null);
    }

    /** 失败告警（默认近 24 小时有失败记录的会话，前 10 条） */
    @GetMapping("/admin/alerts")
    public Result<List<UsageAlertRow>> adminAlerts(@RequestParam(defaultValue = "24") int hours) {
        return Result.ok(usageService.alerts(hours));
    }

    /** 会话漏斗（创建 → 景点 → 美食 → 行程生成 → 完成） */
    @GetMapping("/admin/funnel")
    public Result<List<UsageFunnelStep>> adminFunnel(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo) {
        return Result.ok(usageService.funnel(userId, parseDate(dateFrom), parseDate(dateTo)));
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isAdmin(AuthenticatedUser u) {
        return u != null && "ADMIN".equals(u.role());
    }

    private AuthenticatedUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }
        return null;
    }
}
