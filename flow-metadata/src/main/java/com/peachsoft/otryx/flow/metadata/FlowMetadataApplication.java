package com.peachsoft.otryx.flow.metadata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 项目元数据服务启动类，对外提供 ProjectRpc。 */
@SpringBootApplication
public class FlowMetadataApplication {
    /** 启动元数据服务进程。 */
    public static void main(String[] args) { SpringApplication.run(FlowMetadataApplication.class, args); }
}
