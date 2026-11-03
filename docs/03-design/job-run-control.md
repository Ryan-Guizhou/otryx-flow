# 普通 Job Run 控制

手动执行调用 `JobRunRpc.startManual`，写入 `flow_workflow_run` 和 `flow_job_run_request`。它不创建 Cron 火点，`schedule_id` 为空。

`origin` 为 `CRON`、`MANUAL` 或 `RETRY`。停止先把状态写成 `CANCEL_REQUESTED`，再由取消 Outbox 通知 Dispatcher。已经是 `SUCCEEDED` 或 `FAILED` 的 Run 不会被改成取消。重试始终新建 Run，并通过 `retry_of_run_id` 指向原记录。

立即执行返回 `WAITING_DISPATCH` 只表示 Engine 已接受，不表示 Handler 已成功。
