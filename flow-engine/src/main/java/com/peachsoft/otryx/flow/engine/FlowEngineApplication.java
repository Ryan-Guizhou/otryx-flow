package com.peachsoft.otryx.flow.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 管理逻辑执行实例、Inbox 幂等落库的独立 Engine 服务；不执行 Worker Handler。 */
@EnableScheduling
@SpringBootApplication
public class FlowEngineApplication {
    /** 启动独立 Engine JVM。 */
    public static void main(String[] args) {
        SpringApplication.run(FlowEngineApplication.class, args);
    }
}
