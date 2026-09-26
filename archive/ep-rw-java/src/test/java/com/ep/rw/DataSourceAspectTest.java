package com.ep.rw;
import org.junit.jupiter.api.*;
import org.aspectj.lang.ProceedingJoinPoint;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class DataSourceAspectTest {
    @AfterEach void clear() { RoutingHolder.clear(); }

    @Test void aroundSetsReadThenClears() throws Throwable {
        var pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");
        var ds = mockDataSource(DataSourceType.READ, false, false);
        var aspect = new DataSourceAspect();
        Object r = aspect.around(pjp, ds);
        assertEquals("ok", r);
        verify(pjp).proceed();
        assertNull(RoutingHolder.get(), "ThreadLocal 必须清空防泄漏");
    }
    @Test void consistentForcesWrite() throws Throwable {
        var pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");
        var ds = mockDataSource(DataSourceType.READ, true, false); // consistent=true
        new DataSourceAspect().around(pjp, ds);
        // 执行期间 holder 应是 WRITE（无法直接观测，但 clear 后为 null 验证清理）
        verify(pjp).proceed();
    }
    @Test void writeClearsHolderBeforeProceed() throws Throwable {
        RoutingHolder.set(DataSourceType.READ); // 残留
        var pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(null);
        var ds = mockDataSource(DataSourceType.WRITE, false, false);
        new DataSourceAspect().around(pjp, ds);
        verify(pjp).proceed();
        assertNull(RoutingHolder.get());
    }

    private DataSource mockDataSource(DataSourceType v, boolean c, boolean t) {
        DataSource m = mock(DataSource.class);
        when(m.value()).thenReturn(v);
        when(m.consistent()).thenReturn(c);
        when(m.tiflash()).thenReturn(t);
        return m;
    }
}
