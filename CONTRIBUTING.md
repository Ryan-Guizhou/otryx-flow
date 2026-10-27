# 贡献说明 / Contributing (Proposed)

1. 先阅读 [PRD](docs/01-product/requirements-prd.md)、[架构](docs/02-architecture/architecture-overview.md) 及 [AGENTS](AGENTS.md)。
2. Issue 明确目标、受影响服务、相关 `FR/NFR/AT`、依赖和验收方式；从 `main` 创建短期分支。
3. 一 PR 解决一个 Issue，代码+测试+文档一次性提交，不人为拆出无意义的修修补补 PR。
4. 本阶段可运行的质量命令为 `python3 scripts/check_docs.py`；M1 完成后才加入 Maven/前端构建和端到端测试门禁。
5. 保持中文 Javadoc、英文参数化日志、MySQL Schema 数据归属、原子状态迁移和安全缺省配置；细则以 [Java 工程编码规范](docs/engineering/java-coding-standard.md) 为准。
6. 修改 Java 源码前运行 `python3 scripts/test_check_java_style.py`；PR 中对变更文件执行 `python3 scripts/check_java_style.py --changed --base <base-sha>`，需要了解存量问题时运行 `python3 scripts/check_java_style.py --audit --report target/java-style-audit.json`。
7. PR 完成以准确 Head SHA 的测试结果为依据；不以截图或未来计划代替证据。

**状态：** 当前包仅完成 M0 文档；本说明中的分支和 PR 流程为建立仓库后的治理规则。
