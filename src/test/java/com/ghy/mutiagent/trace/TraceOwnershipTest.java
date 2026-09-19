package com.ghy.mutiagent.trace;

import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.common.ResultCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * S12 查询权限隔离：列表按 owner 过滤；他人链路详情 FORBIDDEN（不区分不存在与无权）。
 */
class TraceOwnershipTest {

    @Test
    void recentFilteredByOwnerAndForeignDetailForbidden() {
        TraceService service = new TraceService();
        TraceContext a = new TraceContext("s-a", "q-a");
        a.setOwnerId(1L);
        service.finish(a);
        TraceContext b = new TraceContext("s-b", "q-b");
        b.setOwnerId(2L);
        service.finish(b);

        List<TraceContext> mine = service.recentForOwner(1L, 10);
        assertEquals(1, mine.size());
        assertEquals("s-a", mine.get(0).getSessionId());

        assertEquals(a, service.detail(1L, "s-a"));
        BizException e = assertThrows(BizException.class, () -> service.detail(1L, "s-b"));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
        assertThrows(BizException.class, () -> service.detail(1L, "s-missing"));
    }
}
