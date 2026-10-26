package com.peachsoft.otryx.flow.scheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 持久化调度微服务，可部署多个独立实例共享同一个 MySQL Schema。 */
@EnableScheduling
@SpringBootApplication
public class FlowSchedulerApplication {
    /** 启动 Scheduler 应用。 */
    public static void main(String[] args) {
        SpringApplication.run(FlowSchedulerApplication.class, args);
    }
}
