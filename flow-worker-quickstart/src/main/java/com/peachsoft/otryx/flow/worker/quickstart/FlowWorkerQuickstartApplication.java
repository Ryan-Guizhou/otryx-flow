package com.peachsoft.otryx.flow.worker.quickstart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 演示独立 JVM 中的可信 Handler 与 OTRYX RPC Consumer 应用级注册。 */
@SpringBootApplication
public class FlowWorkerQuickstartApplication {
    /** 启动不暴露业务 Web Handler 的 Worker 进程。 */
    public static void main(String[] args) {
        SpringApplication.run(FlowWorkerQuickstartApplication.class, args);
    }
}
