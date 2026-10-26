# 关键用户用例（Proposed）

## UC-01 创建并执行定时任务
**主角**：PROJECT_OWNER。**前置**：已存在项目和注册的可信 Java Handler。输入名称、参数、Cron、IANA 时区、重试/并发策略；保存草稿，预览未来 5 次触发后发布。Scheduler 事务化创建计划触发；Worker 领取后执行；前端按 `runId` 查看结果。
**异常**：非法 Cron 拒绝；Handler 未注册拒绝发布；跨时区切换不静默改变既有 schedule 的 IANA 时区；重复 fire 不创建两个逻辑实例。相关 FR-004/005/007/016/019。

## UC-02 DAG 可视化编排
**主角**：PROJECT_OWNER。创建 A→B、A→C、B/C→D，配置独立 Handler。后端校验无环、无断链、版本完整性，发布 revision R1。启动运行后 A 完成，B/C 并行，D 必须等待两者结束才开始。
**异常**：B 失败时按本工作流的 fail-fast 默认策略将尚未开始的下游节点标记不再执行；已有运行尝试需请求取消并按实际结果收敛。R1 执行中发布 R2 不改变 R1。相关 FR-009/010/011/012/013/014。

## UC-03 Worker 宕机恢复
Worker A 领取任务 `(task, attempt 1, epoch 8)`，执行中失联。租约过期后 Dispatcher 创建 attempt 2 / epoch 9 重新分配 Worker B；即使 A 后续恢复，旧 epoch 的提交必须拒绝。业务侧存在重复副作用风险，由 Handler 幂等键消除。相关 FR-015/016/017/018。

## UC-04 演示访客体验
访客打开 HTTPS Demo，浏览执行历史并触发只允许的 `safe-demo-echo` 任务；参数、任务时长、并发和次数被服务端强制限制，不可编辑任意 Handler、SQL、命令或内部网址。相关 FR-002/024。

## UC-05 备份后恢复
停机/一致性备份 → 恢复 MySQL → Flyway 兼容检查 → 启动单个调度节点对账 → 扩容 Worker → 对已过期租约重试，确保不可重放的敏感副作用需人工复核。相关 FR-027/018/023。
