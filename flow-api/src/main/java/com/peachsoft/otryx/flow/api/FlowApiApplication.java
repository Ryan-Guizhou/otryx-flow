package com.peachsoft.otryx.flow.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** HTTP 统一入口及身份认证服务，内部同步调用使用 OTRYX RPC。 */
@SpringBootApplication
public class FlowApiApplication {
    /** 启动 HTTP 服务。 */
    public static void main(String[] args) { SpringApplication.run(FlowApiApplication.class, args); }
}
