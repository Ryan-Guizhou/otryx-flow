# 新 GitHub 仓库初始化步骤（Proposed）

M1 已生成工程源码，但**未创建 `Ryan-Guizhou/otryx-flow`**；GitHub 连接未提供仓库创建 API。下面是用户在有 GitHub CLI 权限的终端可以执行的未来步骤，**本轮未执行**。

```bash
# Run in the extracted otryx-flow project directory after gh auth login.
# 可先在 GitHub 网页端创建空的 Ryan-Guizhou/otryx-flow 再执行 git remote add

git init -b main  # skip if git history already exists
git add .
git commit -m "feat: OTRYX Flow M1 foundation source candidate"  # skip if already committed
gh repo create Ryan-Guizhou/otryx-flow --public --source . --remote origin --push
```

如仓库已经由网页创建，改用：
```bash
git remote add origin https://github.com/Ryan-Guizhou/otryx-flow.git
git push -u origin main
```

之后开启 main Branch Protection、Actions 最小权限、CodeQL/Dependabot（依实际可用功能）、Staging/Production Environment 审批和 GHCR 发布权限。第一批 Issues 来自 [开发 Backlog](issue-backlog.md)，每个 Issue 作为独立 PR 的追踪单元。GitHub Pages 只可承载静态展示文档/前端，不会托管 Java 微服务。

安全提示：创建仓库公开后，先核查文档包中无 Secret、内网地址、证书、未授权第三方视觉资产或个人信息。依赖 OTRYX RPC 的源码/制品按其开源协议和后续发行边界处理。

## 一键整理 Issue 计划（不会自动写入）
从仓库根目录执行 `python3 scripts/publish_issues.py` 可预览 30 个计划 Issue；确保目标仓库、`gh auth login` 与权限就绪后，**显式**执行 `python3 scripts/publish_issues.py --apply` 才会创建缺失 Issue。脚本按 `[FLOW-xxx]` 标识去重，但请人工核对依赖中计划号与创建后的真实 Issue URL。

M1 source currently includes REST/session auth, Etcd OTRYX RPC, Vue UI and isolated Compose. Creation/push are not confirmed until GitHub returns the real repository URL and commit ID.
