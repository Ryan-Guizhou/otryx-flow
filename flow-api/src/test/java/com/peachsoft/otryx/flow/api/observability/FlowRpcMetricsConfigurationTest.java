package com.peachsoft.otryx.flow.api.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.micrometer.MicrometerRpcObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * 验证 RPC Observer 在真正的 Spring 容器中可用，且记录逻辑调用与网络尝试。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/10/30 10:00
 */
class FlowRpcMetricsConfigurationTest {

    @Test
    void bindsRpcObserverToSpringRegistryAndRecordsRealMetricTypes() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.register(FlowRpcMetricsConfiguration.class);
            context.refresh();

            RpcObserver observer = context.getBean("peachRpcMicrometerObserver", RpcObserver.class);
            assertInstanceOf(MicrometerRpcObserver.class, observer);

            MeterRegistry registry = context.getBean(MeterRegistry.class);
            ServiceKey service = new ServiceKey("demo.Service", "1.0.0", "default");
            RpcEndpoint endpoint = new RpcEndpoint("127.0.0.1", 19090);

            observer.onClientAttemptCompleted(service, 1, endpoint, 1, 1000L, RpcStatus.OK, null);
            observer.onClientCallCompleted(service, 1, 2000L, RpcStatus.OK, null);

            assertNotNull(registry.find("otryx.rpc.client.attempts").timer());
            assertNotNull(registry.find("otryx.rpc.client.calls").timer());
            assertEquals(1L, registry.find("otryx.rpc.client.calls").timer().count());
            assertEquals(1L, registry.find("otryx.rpc.client.attempts").timer().count());
        }
    }
}
