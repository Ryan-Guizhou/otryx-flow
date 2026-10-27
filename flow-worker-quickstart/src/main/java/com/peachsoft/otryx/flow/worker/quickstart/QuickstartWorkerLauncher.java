package com.peachsoft.otryx.flow.worker.quickstart;

import com.peachsoft.otryx.flow.contracts.v1.DispatcherRpc;
import com.peachsoft.otryx.flow.worker.sdk.WorkerRuntime;
import com.peachsoft.otryx.flow.worker.sdk.WorkerSettings;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 仅使用预注册 safe.echo 与 safe.fail Handler；绝不将用户参数解释为代码或可执行类名。 */
@Component
public final class QuickstartWorkerLauncher {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuickstartWorkerLauncher.class);

    @OtryxRpcReference(version = "1.0.0")
    private DispatcherRpc dispatcher;

    @Value("${flow.worker.id:flow-worker-demo}")
    private String workerId;

    private WorkerRuntime runtime;

    /** 在 RPC App 消费者初始化后启动真实 Worker SDK。 */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void onReady() {
        if (runtime != null) return;
        runtime = new WorkerRuntime(dispatcher, WorkerSettings.development(workerId), Map.of(
                "safe.echo", (parameters, context) -> {
                    context.checkCancelled();
                    // Intentionally no external I/O; a trusted, side-effect-free example.
                    context.log("INFO", "safe.echo handler executed", Map.of(
                            "handlerKey", "safe.echo",
                            "parameterBytes", Integer.toString(parameters.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)));
                    LOGGER.info("Safe echo handler executed: taskId={}, runId={}, parameterBytes={}",
                            context.taskId(), context.runId(), parameters.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                },
                "safe.fail", (parameters, context) -> {
                    context.checkCancelled();
                    context.log("WARN", "safe.fail handler will fail", Map.of("errorType", "IllegalStateException"));
                    throw new IllegalStateException("intentional test failure");
                }));
        runtime.start();
    }

    /** 启动阶段失败时关闭已创建的运行器，退出时进行有界 Drain。 */
    @PreDestroy
    public synchronized void onStop() {
        if (runtime != null) runtime.close();
    }
}
