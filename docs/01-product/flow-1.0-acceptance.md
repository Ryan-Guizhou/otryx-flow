# Flow 1.0 使用验收

内网开发环境可以登录、修改密码、创建项目和 Job、发布版本、立即执行、停止本次执行、暂停 Cron，以及查看执行和日志。

`ADMIN` 和 `OPERATOR` 可以修改数据。`VIEWER` 和 `DEMO_GUEST` 只能读取。这不是公网生产部署。`NETWORK_MTLS` 仍然会拒绝启动。

首页数字来自当前用户可见的项目、任务、Run 和在线 Worker 心跳。没有心跳时 Workers 显示 0。
