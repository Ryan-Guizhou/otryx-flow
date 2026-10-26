package com.peachsoft.otryx.flow.worker.sdk;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/** SDK 的执行边界、租约续期与退出等待配置，所有限值在构造时校验。 */
public record WorkerSettings(String workerId, int maxConcurrent, Duration pollInterval,
                             Duration heartbeatInterval, Duration maxTaskDuration,
                             Duration drainTimeout) {
    private static final Pattern WORKER = Pattern.compile("[A-Za-z0-9_.-]{3,64}");

    /** 拒绝无界并发、不合法 Worker 身份和不安全的时间配置。 */
    public WorkerSettings {
        if (workerId == null || !WORKER.matcher(workerId).matches()) {
            throw new IllegalArgumentException("Invalid Worker ID");
        }
        Objects.requireNonNull(pollInterval);
        Objects.requireNonNull(heartbeatInterval);
        Objects.requireNonNull(maxTaskDuration);
        Objects.requireNonNull(drainTimeout);
        if (maxConcurrent < 1 || maxConcurrent > 8
                || pollInterval.toMillis() < 100 || pollInterval.toSeconds() > 30
                || heartbeatInterval.toMillis() < 100 || heartbeatInterval.toSeconds() > 10
                || maxTaskDuration.toSeconds() < 1 || maxTaskDuration.toSeconds() > 3600
                || drainTimeout.toSeconds() < 1 || drainTimeout.toSeconds() > 120) {
            throw new IllegalArgumentException("Invalid bounded Worker settings");
        }
    }

    /** 内部开发实例的有限默认配置。 */
    public static WorkerSettings development(String workerId) {
        return new WorkerSettings(workerId, 2, Duration.ofSeconds(1), Duration.ofSeconds(5),
                Duration.ofMinutes(5), Duration.ofSeconds(15));
    }
}
