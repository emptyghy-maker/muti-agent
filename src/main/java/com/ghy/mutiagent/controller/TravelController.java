package com.ghy.mutiagent.controller;

import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.common.Result;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.model.AdjustRequest;
import com.ghy.mutiagent.model.BudgetConfirmRequest;
import com.ghy.mutiagent.model.ChatMessageRequest;
import com.ghy.mutiagent.model.ChatStepResult;
import com.ghy.mutiagent.model.ConfirmCandidatesRequest;
import com.ghy.mutiagent.model.CreateSessionRequest;
import com.ghy.mutiagent.model.FatigueConfirmRequest;
import com.ghy.mutiagent.model.FeedbackRequest;
import com.ghy.mutiagent.model.GenerateItineraryRequest;
import com.ghy.mutiagent.model.GenerateOpRequest;
import com.ghy.mutiagent.model.HistoryItem;
import com.ghy.mutiagent.model.ItineraryDetail;
import com.ghy.mutiagent.model.ItinerarySummary;
import com.ghy.mutiagent.model.PlanQuizRequest;
import com.ghy.mutiagent.model.ResumeView;
import com.ghy.mutiagent.model.RouteResult;
import com.ghy.mutiagent.repository.entity.Destination;
import com.ghy.mutiagent.service.route.RouteService;
import com.ghy.mutiagent.security.AuthenticatedUser;
import com.ghy.mutiagent.service.ItineraryService;
import com.ghy.mutiagent.service.orch.TravelOrchestrator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 旅行规划接口（会话创建 + 偏好问询 + 候选确认 + 行程生成 + 路径查询 + 行程管理）。
 *
 * P0-S01 资源归属约束：所有端点强制登录态（未认证 401，不做匿名回退）；
 * 会话/行程级操作在服务层先做归属校验（404），再进入模型调用与读写。
 */
@RestController
@RequestMapping("/api/v1/travel")
public class TravelController {

    private final TravelOrchestrator orchestrator;
    private final RouteService routeService;
    private final ItineraryService itineraryService;
    /** S06-B 回退演练：只读模式关闭全部写入口，读取与归属校验保持 */
    private final boolean readOnly;
    private final org.springframework.core.task.TaskExecutor taskExecutor;

    public TravelController(TravelOrchestrator orchestrator, RouteService routeService,
                            ItineraryService itineraryService,
                            @org.springframework.beans.factory.annotation.Value("${travel.read-only:false}")
                            boolean readOnly,
                            @org.springframework.beans.factory.annotation.Qualifier("chatStreamExecutor")
                            org.springframework.core.task.TaskExecutor taskExecutor) {
        this.orchestrator = orchestrator;
        this.routeService = routeService;
        this.itineraryService = itineraryService;
        this.readOnly = readOnly;
        this.taskExecutor = taskExecutor;
    }

    /** 创建会话：选定目的地，返回开场白 + 第一个问题 */
    @PostMapping("/session")
    public Result<ChatStepResult> createSession(@RequestBody CreateSessionRequest request) {
        requireWritable();
        AuthenticatedUser u = requireActor();
        return Result.ok(orchestrator.createSession(u, request.getDestinationId()));
    }

    /** 同步对话：推进偏好问询状态机 */
    @PostMapping("/chat/sync")
    public Result<ChatStepResult> chat(@RequestBody ChatMessageRequest request) {
        requireWritable();
        return Result.ok(orchestrator.chat(requireActor(), request.getSessionId(), request.getMessage()));
    }

    /** 确认候选集（selectedIds 为空 = 换一批） */
    @PostMapping("/candidates/confirm")
    public Result<ChatStepResult> confirmCandidates(@RequestBody ConfirmCandidatesRequest request) {
        requireWritable();
        return Result.ok(orchestrator.confirmCandidates(requireActor(), request.getSessionId(),
                request.getCandidateType(), request.getSelectedIds(), request.getRegenerate()));
    }

    /** 同步生成行程（幂等） */
    @PostMapping("/itinerary/generate")
    public Result<ChatStepResult> generateItinerary(@RequestBody GenerateItineraryRequest request) {
        requireWritable();
        return Result.ok(orchestrator.generateItinerary(requireActor(), request.getSessionId()));
    }

    /**
     * 受理生成操作（S06-B）：202 + operationId，执行在后台。
     * 同 requestId 幂等复用（不重生成）；hash 冲突 409；客户端轮询 operation 至终态。
     */
    @PostMapping("/operations/generate")
    public org.springframework.http.ResponseEntity<Result<TravelOrchestrator.OperationAccepted>> generateOp(
            @RequestBody GenerateOpRequest request) {
        AuthenticatedUser u = requireActor();
        requireWritable();
        TravelOrchestrator.OperationAccepted accepted = orchestrator.acceptGenerateOp(u,
                request.getSessionId(), request.getRequestId(), request.getExpectedRevision());
        if (accepted.status() == null
                || com.ghy.mutiagent.service.TravelOperationService.STATUS_RUNNING.equals(accepted.status())) {
            orchestrator.runGenerateOpAsync(u, request.getSessionId(), accepted.operationId());
        }
        return org.springframework.http.ResponseEntity
                .status(org.springframework.http.HttpStatus.ACCEPTED)
                .body(Result.ok(accepted));
    }

    /** 操作查询（S06-B）：先检查 owner；校验类失败以 422 返回具体 violations，其余 200 + 状态视图 */
    @GetMapping("/operations/{id}")
    public org.springframework.http.ResponseEntity<Result<com.ghy.mutiagent.service.TravelOperationService.OperationView>>
    operation(@PathVariable("id") String id) {
        com.ghy.mutiagent.service.TravelOperationService.OperationView v =
                orchestrator.queryOperation(requireActor(), id);
        boolean planInvalid = v.errorCode() != null
                && v.errorCode().startsWith(String.valueOf(ResultCode.PLAN_INVALID.getCode()));
        if (("FAILED".equals(v.status())
                || com.ghy.mutiagent.service.TravelOperationService.STATUS_NEEDS_CONFIRMATION.equals(v.status()))
                && planInvalid) {
            // 422：发布校验违规，携带具体 violations 文案供前端展示（不得静默降级）
            return org.springframework.http.ResponseEntity
                    .status(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Result.fail(ResultCode.PLAN_INVALID.getCode(),
                            v.errorDetail() == null || v.errorDetail().isBlank()
                                    ? "行程校验未通过" : userFacingDetail(v.errorDetail())));
        }
        return org.springframework.http.ResponseEntity.ok(Result.ok(v));
    }

    /** S11 显式取消（SSE 断开同路径）：持久化取消 + 通知在途执行线程；已终态操作幂等 */
    @PostMapping("/operations/{id}/cancel")
    public Result<com.ghy.mutiagent.service.TravelOperationService.OperationView> cancelOperation(
            @PathVariable("id") String id) {
        requireWritable();
        return Result.ok(orchestrator.cancelOperation(requireActor(), id));
    }

    /** S08 终止详情的面向用户部分：结构化 detail 取 message 字段，纯文本原样返回 */
    private String userFacingDetail(String errorDetail) {
        if (errorDetail == null || !errorDetail.trim().startsWith("{")) {
            return errorDetail;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    com.ghy.mutiagent.common.JsonUtils.readTree(errorDetail);
            String message = node.path("message").asText("");
            if (!message.isBlank()) {
                return message;
            }
        } catch (Exception ignored) {
            // 非 JSON detail：原样返回
        }
        return errorDetail;
    }

    /**
     * 操作进度 SSE（S06-B）：只订阅已有操作，不重新执行。
     * 已 COMPLETED 立即回放终态；重连零额外提交。
     */
    @GetMapping("/operations/{id}/stream")
    public SseEmitter operationStream(@PathVariable("id") String id) {
        AuthenticatedUser u = requireActor();
        com.ghy.mutiagent.service.TravelOperationService.OperationView v =
                orchestrator.queryOperation(u, id);
        SseEmitter emitter = new SseEmitter(120_000L);
        if (com.ghy.mutiagent.service.TravelOperationService.STATUS_COMPLETED.equals(v.status())) {
            try {
                emitOp(emitter, "done", id, v.itineraryId(), "COMPLETED", true);
            } catch (Exception ignored) {
                // 客户端已断开：SSE 常态
            }
            return emitter;
        }
        taskExecutor.execute(() -> {
            try {
                for (int i = 0; i < 200; i++) {
                    Thread.sleep(300);
                    com.ghy.mutiagent.service.TravelOperationService.OperationView cur =
                            orchestrator.queryOperation(u, id);
                    if (com.ghy.mutiagent.service.TravelOperationService.STATUS_COMPLETED.equals(cur.status())) {
                        emitOp(emitter, "done", id, cur.itineraryId(), "COMPLETED", true);
                        return;
                    }
                    if (com.ghy.mutiagent.service.TravelOperationService.STATUS_FAILED.equals(cur.status())) {
                        emitOp(emitter, "failed", id, null,
                                cur.errorDetail() == null ? "FAILED" : cur.errorDetail(), true);
                        return;
                    }
                    if (com.ghy.mutiagent.service.TravelOperationService.STATUS_UNKNOWN.equals(cur.status())) {
                        emitOp(emitter, "unknown", id, null, "UNKNOWN", true);
                        return;
                    }
                    if (com.ghy.mutiagent.service.TravelOperationService.STATUS_NEEDS_CONFIRMATION.equals(cur.status())) {
                        emitOp(emitter, "failed", id, null,
                                cur.errorDetail() == null ? "NEEDS_CONFIRMATION" : cur.errorDetail(), true);
                        return;
                    }
                    if (com.ghy.mutiagent.service.TravelOperationService.STATUS_DEADLINE_EXCEEDED.equals(cur.status())) {
                        emitOp(emitter, "failed", id, null,
                                cur.errorDetail() == null ? "DEADLINE_EXCEEDED" : cur.errorDetail(), true);
                        return;
                    }
                    if (com.ghy.mutiagent.service.TravelOperationService.STATUS_CANCELLED.equals(cur.status())) {
                        emitOp(emitter, "failed", id, null, "CANCELLED", true);
                        return;
                    }
                    if (com.ghy.mutiagent.service.TravelOperationService.STATUS_BUSY.equals(cur.status())) {
                        emitOp(emitter, "failed", id, null,
                                cur.errorDetail() == null ? "BUSY" : cur.errorDetail(), true);
                        return;
                    }
                    emitOp(emitter, "processing", id, null, "RUNNING", false);
                }
                emitOp(emitter, "timeout", id, null, "TIMEOUT", true);
            } catch (Exception e) {
                // S11：SSE 断开（客户端消失）→ 停止后续模型步骤（显式取消同路径）；重连只读取状态，不重新执行
                if (e instanceof java.io.IOException) {
                    try {
                        orchestrator.cancelOperation(u, id);
                    } catch (Exception ex) {
                        // 操作已终态：取消幂等无副作用
                    }
                }
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // 客户端断开：SSE 常态
                }
            }
        });
        return emitter;
    }

    /** 当前会话快照（409 冲突后重载 / 202 轮询完成后取已提交结果） */
    @GetMapping("/session/{sessionId}")
    public Result<ChatStepResult> session(@PathVariable("sessionId") String sessionId) {
        return Result.ok(orchestrator.currentSession(requireActor(), sessionId));
    }

    /** 断点恢复：最近一条可恢复的未结束会话（无/过期 data=null；带 sessionId 时校验该会话本身；字面量路径优先于 /session/{sessionId}） */
    @GetMapping("/session/resumable")
    public Result<ResumeView> resumableSession(
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Result.ok(orchestrator.resumableSession(requireActor()));
        }
        return Result.ok(orchestrator.resumableSession(requireActor(), sessionId));
    }

    /** 会话聊天历史回放（恢复窗口用）：先归属校验，越权/不存在统一 404 */
    @GetMapping("/session/{sessionId}/history")
    public Result<java.util.List<HistoryItem>> sessionHistory(@PathVariable("sessionId") String sessionId) {
        return Result.ok(orchestrator.chatHistory(requireActor(), sessionId));
    }

    /** B 方案：疲劳超载待确认草稿裁决（confirm=true 发布行程 / false 返回景点调整） */
    @PostMapping("/session/{sessionId}/fatigue-confirm")
    public Result<ChatStepResult> confirmFatigue(@PathVariable("sessionId") String sessionId,
                                                 @RequestBody FatigueConfirmRequest request) {
        requireWritable();
        return Result.ok(orchestrator.confirmFatigue(requireActor(), sessionId,
                Boolean.TRUE.equals(request.getConfirm())));
    }

    /** 行程偏好问卷提交（PLAN_QUIZ 阶段）：回答起床/回家时间/活动倾向/夜景数量后生成行程 */
    @PostMapping("/session/{sessionId}/quiz-answer")
    public Result<ChatStepResult> submitPlanQuiz(@PathVariable("sessionId") String sessionId,
                                                 @RequestBody PlanQuizRequest request) {
        requireWritable();
        request.setSessionId(sessionId);
        return Result.ok(orchestrator.submitPlanQuiz(requireActor(), request));
    }

    /** 预算超支知情放行裁决（confirm=true 超支自付发布 / false 返回美食调整） */
    @PostMapping("/session/{sessionId}/budget-confirm")
    public Result<ChatStepResult> confirmBudget(@PathVariable("sessionId") String sessionId,
                                                @RequestBody BudgetConfirmRequest request) {
        requireWritable();
        return Result.ok(orchestrator.confirmBudget(requireActor(), sessionId,
                Boolean.TRUE.equals(request.getConfirm())));
    }

    /** 发送失败（客户端断开/超时）向上抛出：调用方据此执行 S11 断开取消；终态幂等 */
    private void emitOp(SseEmitter emitter, String type, String operationId, Long itineraryId,
                        String status, boolean terminal) throws java.io.IOException {
        emitter.send(SseEmitter.event().data("{\"type\":\"" + type + "\",\"operationId\":\""
                + operationId + "\",\"itineraryId\":"
                + (itineraryId == null ? "null" : itineraryId)
                + ",\"status\":\"" + status + "\"}"));
        if (terminal) {
            emitter.complete();
        }
    }

    /** SSE 流式生成行程（建连前同步校验会话归属） */
    @GetMapping("/chat/stream")
    public SseEmitter chatStream(@RequestParam("sessionId") String sessionId) {
        AuthenticatedUser u = requireActor();
        requireWritable();
        // 归属校验先于连接建立：不存在/不属于当前用户直接 404
        orchestrator.requireOwnedSession(u, sessionId);
        SseEmitter emitter = new SseEmitter(300_000L);
        orchestrator.generateItineraryStreaming(u, sessionId, emitter);
        return emitter;
    }

    /** 两点间路径规划（行程地点超链接点击） */
    @GetMapping("/route")
    public Result<RouteResult> route(@RequestParam("sessionId") String sessionId,
                                     @RequestParam("day") int dayIndex,
                                     @RequestParam("fromSeq") int fromSeq,
                                     @RequestParam("toSeq") int toSeq) {
        return Result.ok(routeService.route(requireActor(), sessionId, dayIndex, fromSeq, toSeq));
    }

    /** 基于已落库行程的路径查询（详情页超链接，刷新后仍可用） */
    @GetMapping("/route/itinerary")
    public Result<RouteResult> routeByItinerary(@RequestParam("itineraryId") Long itineraryId,
                                                @RequestParam("day") int dayIndex,
                                                @RequestParam("fromSeq") int fromSeq,
                                                @RequestParam("toSeq") int toSeq) {
        return Result.ok(routeService.routeByItinerary(requireActor(), itineraryId, dayIndex, fromSeq, toSeq));
    }

    /** 可选目的地列表 */
    @GetMapping("/destinations")
    public Result<List<Destination>> destinations() {
        return Result.ok(itineraryService.destinations());
    }

    /** 当前用户的有效行程列表 */
    @GetMapping("/itineraries")
    public Result<List<ItinerarySummary>> itineraries() {
        return Result.ok(itineraryService.listItineraries(requireActor().id()));
    }

    /** 行程详情（偏好 + 结构化行程 + 渲染文本） */
    @GetMapping("/itinerary/{id}")
    public Result<ItineraryDetail> itinerary(@PathVariable("id") Long id) {
        return Result.ok(itineraryService.detail(requireActor(), id));
    }

    /** 基于已有行程生成调整版本 */
    @PostMapping("/itinerary/{id}/adjust")
    public Result<ItineraryDetail> adjust(@PathVariable("id") Long id,
                                          @RequestBody AdjustRequest request) {
        requireWritable();
        return Result.ok(itineraryService.adjust(requireActor(), id, request.getMessage()));
    }

    /** 提交行程评价反馈 */
    @PostMapping("/itinerary/{id}/feedback")
    public Result<Void> feedback(@PathVariable("id") Long id,
                                 @RequestBody FeedbackRequest request) {
        requireWritable();
        itineraryService.submitFeedback(requireActor(), id, request);
        return Result.ok();
    }

    /** S06-B 回退演练：只读模式关闭全部写入口（423），读取与归属校验保持 */
    private void requireWritable() {
        if (readOnly) {
            throw new BizException(ResultCode.READ_ONLY);
        }
    }

    /** 取当前登录用户；未认证直接 401（禁止匿名回退，P0-S01） */
    private AuthenticatedUser requireActor() {
        AuthenticatedUser u = currentUser();
        if (u == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        return u;
    }

    /** 从 SecurityContext 取当前登录用户（由 JwtAuthFilter 放入） */
    private AuthenticatedUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }
        return null;
    }
}
