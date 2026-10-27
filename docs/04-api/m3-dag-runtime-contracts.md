# FLOW-016 DAG 运行时 REST / RPC 契约

**状态：PR #22 待验收。** 接口使用 M1 同源 Session/CSRF，Backend 通过 OTRYX RPC 访问 Engine，API 不共享 Engine 数据库。

## REST

| 方法 | URL | 结果 |
|---|---|---|
| POST | /api/v1/projects/{projectId}/workflow-runs | ADMIN + CSRF + Idempotency-Key；固定 WorkflowRevision 创建 Run。201 或相同 key 重放 200 |
| GET | /api/v1/projects/{projectId}/workflow-runs | 登录用户所属项目有界实例列表，最多 100 |
| GET | /api/v1/projects/{projectId}/workflow-runs/{runId} | 登录用户项目内 Run 与 NodeRun 状态，隐藏 leaseToken |

创建 body：`{"workflowId":"UUID","workflowRevision":1,"inputJson":"{}"}`。inputJson 仅允许 16 KiB 内的 JSON 对象。server principal 产生 owner，浏览器无法伪造租户。输入无效 400，跨项目或无修订版本 404，同键修改正文 409，未登录 401，CSRF/角色拒绝 403。

## OTRYX RPC

`DagRpc.start(DagStartCommand)`、`list(owner,project)`、`detail(owner,project,runId)`，版本 1.0.0。创建幂等唯一键 `(owner_username,request_key)`，输入、owner、项目、工作流/版本共同组成 SHA-256 指纹。返回的 graphSha256 固定到 Run，不从可变草稿读取。

NodeRun 独立 ID 作为 Dispatcher `DispatchEnqueueCommand.runId`，父 DagRun ID 作为临时 `scheduleId`，WorkflowDefinition ID 作为临时 `jobId`；结果经 `EngineRpc.acknowledgeTaskOutcome` 路由到节点专属 Inbox，返回 ACCEPTED/DUPLICATE/CONFLICT，不能把单节点完成等同于父 Run 成功。

状态：BLOCKED、READY、DISPATCHED、SUCCEEDED、FAILED、SKIPPED；父 Run RUNNING、SUCCEEDED、FAILED。失败采用 fail-fast，不撤销已发任务。参见[设计](../03-design/m3-dag-runtime.md)与[验收](../06-testing/m3-dag-acceptance.md)。

