package com.ghy.mutiagent.service.route;

import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.security.AuthenticatedUser;
import com.ghy.mutiagent.service.TravelSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * S10 事实工具网关契约：越权/非法参数/写操作意图在调用任何提供方之前拒绝。
 */
@ExtendWith(MockitoExtension.class)
class FactToolServiceTest {

    @Mock
    private TravelSessionService sessionService;

    private final AuthenticatedUser actor = new AuthenticatedUser(1L, "owner", "USER");
    private FactToolService tools;

    @BeforeEach
    void setUp() {
        tools = new FactToolService(sessionService);
    }

    @Test
    void foreignSessionForbiddenBeforeProvider() {
        when(sessionService.loadOwned(anyString(), anyLong()))
                .thenThrow(new BizException(ResultCode.RESOURCE_NOT_FOUND));
        AtomicInteger providerCalls = new AtomicInteger();
        tools.registerProvider(FactToolService.TOOL_WEATHER, args -> {
            providerCalls.incrementAndGet();
            return null;
        });
        FactToolService.ToolResult r = tools.dispatch(actor, "s-foreign", FactToolService.TOOL_WEATHER,
                Map.of("lat", 31, "lng", 121));
        assertEquals(FactToolService.ERR_FORBIDDEN, r.errorCode());
        assertEquals(0, providerCalls.get());
    }

    @Test
    void illegalLatitudeRejected() {
        when(sessionService.loadOwned(anyString(), anyLong())).thenReturn(new TravelState());
        FactToolService.ToolResult r = tools.dispatch(actor, "s-own", FactToolService.TOOL_WEATHER,
                Map.of("lat", 100, "lng", 121));
        assertEquals(FactToolService.ERR_INVALID_TOOL_ARGUMENT, r.errorCode());
    }

    @Test
    void writeToolNotAllowed() {
        when(sessionService.loadOwned(anyString(), anyLong())).thenReturn(new TravelState());
        FactToolService.ToolResult r = tools.dispatch(actor, "s-own", "PURCHASE_TICKET",
                Map.of("lat", 31, "lng", 121));
        assertEquals(FactToolService.ERR_TOOL_NOT_ALLOWED, r.errorCode());
    }

    @Test
    void validToolReachesProvider() {
        when(sessionService.loadOwned(anyString(), anyLong())).thenReturn(new TravelState());
        AtomicInteger providerCalls = new AtomicInteger();
        tools.registerProvider(FactToolService.TOOL_WEATHER, args -> {
            providerCalls.incrementAndGet();
            return null;
        });
        FactToolService.ToolResult r = tools.dispatch(actor, "s-own", FactToolService.TOOL_WEATHER,
                Map.of("lat", 31, "lng", 121));
        assertNull(r.errorCode());
        assertEquals(1, providerCalls.get());
    }
}
