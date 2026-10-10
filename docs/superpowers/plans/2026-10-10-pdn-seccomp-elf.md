# PDN 原始 ELF seccomp 加速 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement and review each bounded task.

**Goal:** 恢复允许安全启用的宿主环境中的 PRoot 加速，交付原始 ELF，不修改 AAR SDK 或构建 APK。

**Architecture:** tracer 在 fork 前读取宿主 PR_GET_SECCOMP；仅查询结果为 0 且不存在 PROOT_NO_SECCOMP 时安装 PRoot 过滤器。继承过滤器或查询失败继续完整追踪。恢复事件循环条件式 PTRACE_CONT，保留链式调用/寄存器恢复所需的 PTRACE_SYSCALL。

**Tech Stack:** C、PRoot ptrace/seccomp、ARM64 Android NDK、Python unittest、LLVM coverage、Android shell。

## 实施与验证

- [x] event.c 增加 sys/prctl.h，在 launch_tracee 的 fork 前读取宿主模式并决定是否安装过滤器；子进程 SIGSTOP 后按该结果调用 enable_syscall_filtering。
- [x] restart_how 未显式设置时，仅 ENABLED 且不需要 sysexit 使用 PTRACE_CONT；其余使用 PTRACE_SYSCALL。
- [x] pdn.c 移除强制设置 PROOT_NO_SECCOMP 的语句，保留显式禁用变量的传统语义。
- [x] 扩展真实 C probe：继承 allow 过滤器、PR_GET_SECCOMP 被明确拒绝后执行候选，验证安全回退和功能；不添加发行测试开关。
- [x] 版本增加至 0.6.7，同步 User-Agent、版本断言与原生 CLI 当前版本文档；已发布 AAR 保持 0.6.6。
- [x] NDK 构建正常 ELF；先运行 proot/pdn 与新策略测试，再运行相关启动、事件、配置和归档回归。
- [x] Android shell 无继承过滤器时验证加速日志、SIGSYS、fork/exec、身份、真实 Alpine 路径测试；显式禁用、继承过滤器及查询失败验证没有加速。
- [x] 同一 ELF、同一 Alpine 执行环境下进行加速/禁用任务对照，排除建连及安装耗时。
- [x] LLVM 新增/修改可执行行覆盖率达到 80%；规格及质量评审通过。
- [x] 文档记录范围、启用方式和实际性能；交付原始 ELF/loader、.so 副本及校验值到 /sdcard/yyd/PDN，保留历史产物，不发布新 Release。
- [x] 清理本轮临时 rootfs/构建和原始采集数据，保留正常交付与必要证据，提交并推送。

LD_PRELOAD 反馈继续仅记录，不修复、不测试；不改变 Android SIGSYS 参数处理，也不尝试为已有 seccomp 模式 2 的普通 App 默认启用加速。

完成结果：0.6.7 原始 ELF 已交付，原生 222 通过 / 2 跳过，Android shell 策略 10/10、加速路径 14/14，修改行覆盖率 85.71%。tar/gzip 用时中位数减少 53.63%；样本与范围限制见 docs/pdn-error-testing.md。AAR/APK 未重建，未发布新 Release，LD_PRELOAD 未修复或测试。
