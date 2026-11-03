# Flow 1.0 验证记录

日期：2026-11-03。

JDK 21 下，flow-dispatcher 22、flow-worker-sdk 7、flow-api 32、flow-metadata 18 个单元测试在补齐详情和授权后通过。更早一轮里 flow-scheduler 16 和 flow-engine 29 也已通过。`vue-tsc` 通过。

## Playwright（本机 Chrome，模拟接口）

工作目录 `flow-web`。这 4 条用例不连接 Compose，接口由测试自己伪造。

| 时间 | 命令 | 结果 |
|---|---|---|
| 15:02 | `npm run typecheck` 后接 `npm run e2e` | 类型检查通过。页面测试失败：`App.vue` 概览区缺少闭合标签，第 1 条用例 5.4 秒失败，进程随后被停掉 |
| 15:03 | `npm run e2e` | 3 通过，1 失败，共 33.9 秒。失败用例是 “job handler field only accepts a safe prefix”：新建任务弹层还开着，挡住了 Sign out |
| 15:04 | `npm run e2e` | 4 通过，共 4.6 秒 |

最后一次通过的用例：

| 用例 | 耗时 | 检查到的行为 |
|---|---|---|
| login form is shown before a session exists | 438ms | 未登录时看到 Sign in to OTRYX Flow |
| admin can open workers, limits, and audit | 643ms | 管理员能打开 Worker drain、保存限额、看到 Change history |
| demo guest can read limits but cannot save them | 516ms | 访客能看限额，Save limits 不可用 |
| job handler field only accepts a safe prefix | 660ms | 能打开 Edit draft；Handler 只接受 `safe.*`；退出前出现 Sign out? |

`playwright-report/` 和 `test-results/` 被 `.gitignore` 排除，所以上表是这次可保留的文字记录。真实后端用例 `e2e/flow-1.0-real.spec.ts` 不在 `npm run e2e` 里。

`flow-web/e2e/flow-1.0-real.spec.ts` 会连 `http://127.0.0.1:8088`。本次检查时 Compose 没有起来（缺少 `.env` 里的 `OTRYX_RPC_SHA`），所以这条真实浏览器路径没有执行，不能记为 AT-23 或 AT-24 通过。双副本故障、Worker 崩溃恢复和配额并发也没有新的实测。

生产 mTLS、GHCR 推送和 Release Tag 没有做。
