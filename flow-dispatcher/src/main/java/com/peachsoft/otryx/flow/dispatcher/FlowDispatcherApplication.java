package com.peachsoft.otryx.flow.dispatcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Dispatcher 独立应用：持久化队列与 Lease/Fencing；不会执行 Handler。 */
@EnableScheduling
@SpringBootApplication
public class FlowDispatcherApplication {
    /** 启动 Dispatcher 服务。 */
    public static void main(String[] args) { SpringApplication.run(FlowDispatcherApplication.class, args); }
}
