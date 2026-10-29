package com.peachsoft.otryx.flow.worker.quickstart;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import com.peachsoft.otryx.flow.contracts.v1.DispatcherRpc;
import com.peachsoft.otryx.flow.worker.sdk.WorkerRuntime;
import com.peachsoft.otryx.flow.worker.sdk.WorkerSettings;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;

/**
 * 启动受限的 Quickstart Worker，并发布运行时指标。
 *
 * <p>仅注册 safe.echo 与 safe.fail 两个固定 Handler；用户参数不会被解释为代码、脚本或类名。</p>
 *
 * @Author Ryan
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/10/26 18:20
 */
@Component
public final class QuickstartWorkerLauncher {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuickstartWorkerLauncher.class);

    private static final String HANDLER_SAFE_ECHO = "safe.echo";
    private static final String HANDLER_SAFE_FAIL = "safe.fail";
    private static final String LOG_LEVEL_INFO = "INFO";
    private static final String LOG_LEVEL_WARN = "WARN";

    private static final String METRIC_AVAILABLE_SLOTS = "flow.worker.available_slots";
    private static final String METRIC_ACTIVE_TASKS = "flow.worker.active_tasks";
    private static final String METRIC_EXECUTION_SECONDS = "flow.worker.execution.duration.total.seconds";
    private static final String METRIC_TASKS_COMPLETED = "flow.worker.tasks.completed";
    private static final String METRIC_TASKS_FAILED = "flow.worker.tasks.failed";
    private static final String METRIC_TASKS_CANCELLED = "flow.worker.tasks.cancelled";
    private static final String METRIC_LOGS_DROPPED = "flow.worker.logs.dropped";

    private final MeterRegistry meterRegistry;
    private WorkerRuntime runtime;

    @OtryxRpcReference(version = "1.0.0")
    private DispatcherRpc dispatcher;

    @Value("${flow.worker.id:flow-worker-demo}")
    private String workerId;

    /**
     * 注入进程级指标注册器。
     *
     * @param meterRegistry 当前 Worker JVM 的指标注册器
     */
    public QuickstartWorkerLauncher(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    /**
     * 在 Spring Boot 启动完成且 OTRYX RPC 消费者就绪后创建 Worker。
     *
     * <p>重复事件不会重复创建线程或重复注册指标。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void onReady() {
        if (runtime != null) {
            return;
        }

        runtime = new WorkerRuntime(
                dispatcher,
                WorkerSettings.development(workerId),
                Map.of(
                        HANDLER_SAFE_ECHO,
                        (parameters, context) -> {
                            context.checkCancelled();
                            int parameterBytes = parameters.getBytes(StandardCharsets.UTF_8).length;
                            context.log(
                                    LOG_LEVEL_INFO,
                                    "safe.echo handler executed",
                                    Map.of(
                                            "handlerKey", HANDLER_SAFE_ECHO,
                                            "parameterBytes", Integer.toString(parameterBytes)));
                            LOGGER.info(
                                    "Safe echo handler executed: taskId={}, runId={}, parameterBytes={}",
                                    context.taskId(),
                                    context.runId(),
                                    parameterBytes);
                        },
                        HANDLER_SAFE_FAIL,
                        (parameters, context) -> {
                            context.checkCancelled();
                            context.log(
                                    LOG_LEVEL_WARN,
                                    "safe.fail handler will fail",
                                    Map.of("errorType", IllegalStateException.class.getSimpleName()));
                            throw new IllegalStateException("intentional test failure");
                        }));

        runtime.start();
        Gauge.builder(METRIC_AVAILABLE_SLOTS, runtime, value -> value.availableSlots())
                .description("Number of Worker execution slots currently available")
                .register(meterRegistry);
        Gauge.builder(METRIC_ACTIVE_TASKS, runtime, value -> value.activeCount())
                .description("Number of tasks currently owned by this Worker process")
                .register(meterRegistry);
        Gauge.builder(
                        METRIC_EXECUTION_SECONDS,
                        runtime,
                        value -> value.totalExecutionNanos() / 1_000_000_000.0)
                .description("Cumulative Worker handler execution time in seconds")
                .register(meterRegistry);
        FunctionCounter.builder(METRIC_TASKS_COMPLETED, runtime, value -> value.completedTaskCount())
                .description("Cumulative number of completed Worker tasks")
                .register(meterRegistry);
        FunctionCounter.builder(METRIC_TASKS_FAILED, runtime, value -> value.failedTaskCount())
                .description("Cumulative number of failed Worker tasks")
                .register(meterRegistry);
        FunctionCounter.builder(METRIC_TASKS_CANCELLED, runtime, value -> value.cancelledTaskCount())
                .description("Cumulative number of cancelled Worker tasks")
                .register(meterRegistry);
        FunctionCounter.builder(METRIC_LOGS_DROPPED, runtime, value -> value.droppedLogCount())
                .description("Cumulative number of Worker log records dropped by bounded delivery")
                .register(meterRegistry);
    }

    /**
     * 应用关闭时关闭已创建的运行器，并执行有界 Drain。
     *
     * <p>WorkerRuntime.close() 幂等；超时取消不保证业务 Handler 的外部副作用回滚。</p>
     */
    @PreDestroy
    public synchronized void onStop() {
        if (runtime != null) {
            runtime.close();
        }
    }
}
