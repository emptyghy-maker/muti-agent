package com.ghy.mutiagent.service.route;

import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.model.RouteFact;
import com.ghy.mutiagent.security.AuthenticatedUser;
import com.ghy.mutiagent.service.TravelSessionService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * S10 事实工具网关：模型只能提出工具意图，这里在调用任何提供方之前完成
 * 归属校验、工具白名单（只读：天气/营业时间/路线）与参数校验（经纬度范围）。
 * 不接受模型提供的任意 URL；预约/购票等写操作一律 TOOL_NOT_ALLOWED。
 */
public class FactToolService {

    public static final String TOOL_WEATHER = "WEATHER";
    public static final String TOOL_OPENING_HOURS = "OPENING_HOURS";
    public static final String TOOL_ROUTE = "ROUTE";

    public static final String ERR_FORBIDDEN = "FORBIDDEN";
    public static final String ERR_INVALID_TOOL_ARGUMENT = "INVALID_TOOL_ARGUMENT";
    public static final String ERR_TOOL_NOT_ALLOWED = "TOOL_NOT_ALLOWED";

    private static final Set<String> READ_TOOLS = Set.of(TOOL_WEATHER, TOOL_OPENING_HOURS, TOOL_ROUTE);

    public record ToolResult(String errorCode, RouteFact fact) {
        public boolean ok() {
            return errorCode == null;
        }
    }

    private final TravelSessionService sessionService;
    private final Map<String, Function<Map<String, Object>, RouteFact>> providers = new LinkedHashMap<>();

    /** Observability 薄埋点（可空：手动装配的测试进程为 null；模块关闭时内部 noop） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ghy.mutiagent.observability.collection.ObsInstrumentation obsInstrumentation;

    public FactToolService(TravelSessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** 注册只读工具提供方（真实提供方或门禁桥接替身）；未注册的工具不可调用 */
    public void registerProvider(String tool, Function<Map<String, Object>, RouteFact> provider) {
        providers.put(tool, provider);
    }

    public ToolResult dispatch(AuthenticatedUser actor, String sessionId, String tool,
                               Map<String, Object> args) {
        // 1. 归属：工具作用于会话级事实，会话必须属于当前用户（越权即 FORBIDDEN，不探测资源）
        try {
            sessionService.loadOwned(sessionId, actor.id());
        } catch (BizException e) {
            obsToolRejected(null, sessionId, actor, tool, ERR_FORBIDDEN);
            return new ToolResult(ERR_FORBIDDEN, null);
        }
        // 2. 白名单：只读工具；任何写操作（购票等）在调用前拒绝
        if (!READ_TOOLS.contains(tool)) {
            obsToolRejected(null, sessionId, actor, tool, ERR_TOOL_NOT_ALLOWED);
            return new ToolResult(ERR_TOOL_NOT_ALLOWED, null);
        }
        // 3. 参数：经纬度范围
        Double lat = number(args == null ? null : args.get("lat"));
        Double lng = number(args == null ? null : args.get("lng"));
        if (lat == null || lng == null || Math.abs(lat) > 90 || Math.abs(lng) > 180) {
            obsToolRejected(null, sessionId, actor, tool, ERR_INVALID_TOOL_ARGUMENT);
            return new ToolResult(ERR_INVALID_TOOL_ARGUMENT, null);
        }
        // 4. 白名单工具 → 只读提供方
        Function<Map<String, Object>, RouteFact> provider = providers.get(tool);
        if (provider == null) {
            obsToolRejected(null, sessionId, actor, tool, ERR_INVALID_TOOL_ARGUMENT);
            return new ToolResult(ERR_INVALID_TOOL_ARGUMENT, null);
        }
        RouteFact fact = provider.apply(args);
        obsToolOk(null, sessionId, actor, tool, fact);
        return new ToolResult(null, fact);
    }

    // ==================== Observability 薄埋点（可空，无副作用） ====================

    private void obsToolOk(String operationId, String sessionId, AuthenticatedUser actor,
                           String tool, RouteFact fact) {
        if (obsInstrumentation != null) {
            obsInstrumentation.toolEvent(operationId, sessionId, actor == null ? null : actor.id(),
                    tool, "TOOL_OK", false,
                    java.util.Map.of("factId", fact == null || fact.factId() == null ? "" : fact.factId(),
                            "source", fact == null || fact.source() == null ? "" : fact.source()));
        }
    }

    private void obsToolRejected(String operationId, String sessionId, AuthenticatedUser actor,
                                 String tool, String errorCode) {
        if (obsInstrumentation != null) {
            obsInstrumentation.toolEvent(operationId, sessionId, actor == null ? null : actor.id(),
                    tool, "REJECTED", false, java.util.Map.of("errorCode", errorCode));
        }
    }

    private static Double number(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
