package com.peachsoft.otryx.flow.api.observability;

import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.micrometer.MicrometerRpcObserver;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 显式绑定 API 进程的 OTRYX RPC 低基数 Micrometer Observer。
 *
 * <p>RPC Runtime 在首次构造 Consumer 时读取 Observer。显式注册名称与 OTRYX
 * 自动配置的条件名称一致，避免 MeterRegistry 条件装配时序导致运行时缺失指标。
 * 本配置不创建额外线程、网络连接或任何高基数标签。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/10/30 10:00
 */
@Configuration(proxyBeanMethods = false)
public class FlowRpcMetricsConfiguration {

    /**
     * 为整个 API 进程创建唯一的 RPC Micrometer 观察者。
     *
     * @param registry Spring Boot 管理的进程级指标注册器
     * @return 与 RPC Core 无关业务身份信息的观察者
     */
    @Bean("peachRpcMicrometerObserver")
    public RpcObserver flowRpcObserver(MeterRegistry registry) {
        return new MicrometerRpcObserver(registry);
    }
}
