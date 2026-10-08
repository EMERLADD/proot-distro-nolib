# PDN 错误分类完善 Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development for bounded module implementation, then review specification compliance and code quality before delivery.

**Goal:** 将管理命令与 Java 宿主失败分类到实际失败点，保留退出码、异常兼容和镜像回退；不细化 PRoot/guest 启动内部错误。

**Architecture:** 原生沿用协议 v1，在失败位置显式发送 code/message/suggestion；公共系统错误函数解释已保存的 errno，泛化包装不覆盖具体诊断。Java 增加 IOException 子类提供宿主错误码与建议，保持回调异常和线程中断原样传播。

**Tech Stack:** C、Java、Kotlin、Python unittest、LLVM 覆盖率与 JaCoCo。

- [x] 公共事件错误映射及包装保留原因测试。
- [x] 前端、目录、参数、配置、挂载及卸载分类与针对性测试。
- [x] 安装、下载、校验、归档、冲突与锁分类及针对性测试。
- [x] Java 宿主异常分类、异常兼容与失败清理测试。
- [x] 原生回归及覆盖率 >=80%；Java/Kotlin 覆盖率 >=80%；原 App Kotlin 编译。
- [x] 逐项测试触发说明，明确真实失败、测试注入与未验证情况。
- [x] PDN 0.6.3、App 1.0.3/code4，文档与下载 User-Agent 同步；提交推送并检查包含 AAR/ELF/.so 的自动 Release。

本次不构建原 App APK；AAR 与原生程序按发布流程构建。

验证与范围见 [逐项错误测试](../../pdn-error-testing.md)。提交后核对自动 Release 附件。
