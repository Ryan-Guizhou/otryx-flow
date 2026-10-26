# REST OpenAPI 契约草案（Proposed）

## 入口规则
- 对外 HTTP 前缀 `/api/v1`，所有项目资源必须按当前用户授权范围过滤。接口都是**拟议**，尚无 Controller；M1 现有最小接口见 [M1 契约](m1-implemented-contracts.md)；其余接口仍为拟议。OpenAPI 3.1 导出仍待实现与验收。
- 客户端状态修改包含 CSRF 机制；重试敏感创建操作使用 `Idempotency-Key`（由服务端在项目范围内保存去重），请求与响应设定有界大小和明确校验错误。
- 统一时间为 ISO 8601 UTC；用户输入 Cron 须同时提交 IANA `timeZone`，前端只把 UTC 时间格式化为用户时区展示。
- 错误响应统一 `code` / `message` / `requestId` / `details`，禁止泄露堆栈、数据库信息、内部 RPC 细节。

## API 清单
| 方法 | 路径 | 权限 | 行为 |
|---|---|---|---|
| `POST` | `/auth/login` | anonymous, rate-limited | 登录，会话/CSRF |
| `POST` | `/auth/logout` | signed-in | 销毁会话 |
| `GET` | `/me` | signed-in | 当前用户与项目权限 |
| `GET,POST` | `/projects` | user/admin | 列表/创建项目 |
| `GET,PUT` | `/projects/{p}/members` | project owner | 成员授权 |
| `GET,POST` | `/projects/{p}/jobs` | viewer/operator | 查询/新建任务定义 |
| `POST` | `/projects/{p}/jobs/{j}/publish` | owner | 发布不可变 Revision |
| `POST` | `/projects/{p}/jobs/{j}/run` | operator | 手动执行，要求幂等键 |
| `GET,POST` | `/projects/{p}/schedules` | viewer/owner | 配置 Cron/TZ/misfire |
| `POST` | `/projects/{p}/schedules/preview` | operator | 未来触发时间预览 |
| `GET,POST` | `/projects/{p}/workflows` | viewer/owner | 工作流列表/新建 |
| `PUT` | `/projects/{p}/workflows/{w}/draft` | owner | 草稿保存（ETag/version CAS） |
| `POST` | `/projects/{p}/workflows/{w}/validate` | owner | 后端 DAG 校验 |
| `POST` | `/projects/{p}/workflows/{w}/publish` | owner | 发布版本 |
| `POST` | `/projects/{p}/workflows/{w}/run` | operator | 创建运行实例（返回 202） |
| `GET` | `/projects/{p}/runs/{r}` | viewer | Run/Node 状态和 Revision |
| `GET` | `/projects/{p}/runs/{r}/attempts` | viewer | Attempt 详情、Worker、失败原因 |
| `GET` | `/projects/{p}/runs/{r}/logs` | viewer | 分页/游标日志、脱敏 |
| `POST` | `/projects/{p}/runs/{r}/cancel` | operator | 申请取消，返回最新中间状态 |
| `POST` | `/projects/{p}/runs/{r}/retry` | operator | 受控重试，留审计 |
| `GET` | `/projects/{p}/workers` | viewer | 状态、容量、分组、标签 |
| `GET` | `/projects/{p}/audit` | owner | 审计过滤 |

## 创建工作流运行的样例（提议格式）
```http
POST /api/v1/projects/PROJECT_ID/workflows/WORKFLOW_ID/run
Idempotency-Key: demo-example-run-001
Content-Type: application/json

{"revision":3,"input":{"scenario":"safe-demo-echo"}}
```

```json
{
  "runId":"01H-ILLUSTRATIVE-ONLY",
  "status":"CREATED",
  "requestId":"req-example",
  "acceptedAt":"2026-10-23T12:00:00Z"
}
```

这是设计示意，不保证为最终数据类型，不能直接当成已运行服务返回。创建成功仅表示持久化受理，不是执行成功。

## HTTP 与 RPC 语义映射
`400` 参数格式；`401` 未登录；`403` 项目越权；`404` 不存在或不可见；`409` Revision/CAS 冲突或非法状态；`422` DAG/Cron 业务校验；`429` 配额拒绝；`503` 内部服务不可用（不泄露身份/拓扑），`202` 已受理异步命令。所有非幂等创建都必须可用稳定幂等键查到原响应。

## OpenAPI 自动化门禁
M1 生成 OpenAPI YAML 并提交版本控制；后续每个 PR 检查 Breaking Change、示例字段和前端类型生成。Endpoint 鉴权/项目隔离必须有契约测试，不能只测试 Controller HTTP 200。
