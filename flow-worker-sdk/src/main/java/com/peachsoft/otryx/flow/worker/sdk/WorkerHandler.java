package com.peachsoft.otryx.flow.worker.sdk;

/** 仅由部署者显式注册的 Java Handler；不得从远端参数解析反射类名或执行脚本。 */
@FunctionalInterface
public interface WorkerHandler {
    /**
     * 执行受信任的逻辑任务，须响应线程中断和上下文取消。
     *
     * @param parametersJson 已发布不可变任务版本的有界 JSON 参数
     * @param context 当前任务只读标识和协作取消信号
     * @throws Exception 执行失败，将报告为当前租约的 FAILED
     */
    void execute(String parametersJson, WorkerContext context) throws Exception;
}
