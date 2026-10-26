# ADR-003：生产内部通信使用 OTRYX RPC mTLS

**状态：Proposed / 2026-10-23**

**决策**：内部同步请求统一 OTRYX RPC + 受保护 Etcd Registry。Dev 可信隔离可以显式 `TRUSTED_DEVELOPMENT`；Staging/Demo/Production 要求 `NETWORK_MTLS`、实际证书绑定、安全 Codec 和 Provider 显式授权；RPC 业务 Metadata 不是 Peer 身份证明。

**理由**：与 OTRYX RPC 目前已明确的安全契约一致，避免将网络身份简化为网关传入的 userId。业务 Actor 对下游传播通过可验证的签名短期委托，并按资源/项目授权。

**后果**：环境需要 CA、证书签发/轮换、Etcd TLS/RBAC、密钥保护及双向网络连通性测试；提高部署复杂度，但这是公网业务的安全门槛。

**来源**：[OTRYX RPC 安全文档](https://github.com/Ryan-Guizhou/otryx-rpc/blob/main/docs/security.md)。
