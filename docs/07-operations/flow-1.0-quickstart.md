# Flow 1.0 本地启动

在仓库根目录用 Git Bash 或 WSL 执行：

```bash
./scripts/dev.sh up
```

PowerShell 可以写成 `bash ./scripts/dev.sh up`。脚本会在没有 `.env` 时生成本地随机密码，然后构建并启动 MySQL、Nacos、各业务服务、Web 和示例 Worker。站点是 `http://127.0.0.1:8088`。用户名是 `.env` 里的 `FLOW_ADMIN_USERNAME`，密码是 `FLOW_ADMIN_PASSWORD`。这套 Compose 里的 MySQL 和 Nacos 不映射到宿主机，登录页也不使用本机安装的 Nacos 或 MySQL 账号。

常用命令：

```bash
./scripts/dev.sh ps
./scripts/dev.sh logs flow-api
./scripts/dev.sh down
```

`down` 停容器并保留数据库。不要把开发用的 `TRUSTED_DEVELOPMENT` 暴露到公网。

页面操作：

1. 打开站点，用 `.env` 里的管理员登录。刷新后会话仍在。
2. Projects 里创建项目。
3. Jobs 里选择项目，New job 填写名称。Handler 用 `safe.summarize`，参数用下面的 JSON。Save draft 只保存草稿。
4. Publish revision 后再点 Run now。没有在线 Worker 时页面会提示 Handler 不可用，不会显示成执行成功。
5. Executions 查看状态、失败原因、耗时、Attempt 和日志。Stop this run 只停这一次；Pause schedule 只停以后的 Cron。
6. 侧栏 `*` 修改自己的密码，成功后当前会话结束。`↪` 会先询问再退出。

只跑前端模拟测试时，在 `flow-web` 执行 `npm run e2e`。要连已经启动的 `http://127.0.0.1:8088`，在仓库根目录执行 `bash scripts/test_flow_1_0_e2e.sh`。栈没起来时，脚本会写明 AT-23 未执行。

示例 Worker 注册 `safe.echo`、`safe.fail` 和 `safe.summarize`。发布任务时选择 `safe.summarize`，参数示例：

```json
{"values":[2,3,5]}
```

点击 Run now 后，执行中心应出现 `WAITING_DISPATCH`，Worker 领取后最终成为 `SUCCEEDED` 或 `FAILED`。日志中可以核对 `summarize.sum=10`。暂停 Cron 不会取消已经存在的 Run；停止按钮只作用于选中的那一次执行。
