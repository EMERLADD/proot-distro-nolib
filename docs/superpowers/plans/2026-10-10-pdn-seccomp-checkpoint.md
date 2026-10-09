# PDN seccomp 优化：任务阶段保存

保存日期：2026-10-10。任务按要求暂时停止，恢复后从本文继续。

## 当前阶段

已经完成清理与源码排查，尚未修改 seccomp 实现、重新编译或进行新一轮测速。当前 PDN 源码和原生构建为 0.6.6；此前 R8 验收已提交并推送，提交为 `6ba35b4`。

下一项明确授权：先修改 seccomp 并重新编译原始 pdn ELF，暂不修改 AAR SDK 或构建 APK。这里是源码 patch 与重编译，不是使用 patchelf 修改加载器/RPATH。

## 已完成

- 中英文 README、当前待办和错误测试记录已同步。R8 Release 实机验收：AAR 47/47、直接 .so 39/39；直接 .so 的 waitpid 状态修复已提交。
- 本轮临时目录、两份 probe 的 Gradle 构建输出和输入副本、测速用 `pdn-bench-20261010` 容器、Android 临时测试目录与两个 probe 的私有测试数据已经清理。
- 最终 ELF/AAR/APK、历史版本、源码、文档与交付报告保留。个人 Termux Alpine/Ubuntu 保留。
- `build/path-boundary`、`build/r8-acceptance`、`build/task-benchmark`、`build/startup-benchmark`、`build/proxy-diagnosis` 已删除，不要假设其中测试脚本/日志仍存在。
- 验收和测速记录仍在 `/sdcard/yyd/PDN/path-boundary-v066/`、`r8-v066/`、`task-benchmark-v066/`。临时覆盖率 APK/原始采集缓存已删除，摘要和结果保留。

## 性能证据与代理问题

此前同一 Alpine、相同 guest 包版本的任务中位数：

| 任务 | Termux proot-distro | Android shell PDN |
| --- | ---: | ---: |
| tar/gzip | 2110.9 ms | 3374.3 ms |
| C 编译 | 3650.5 ms | 7105.8 ms |

给 Termux proot-distro 设置 `PROOT_NO_SECCOMP=1` 后，分别为 3338.3 ms 与 6880.3 ms，说明加速策略是这两个场景差距的重要因素。不能由此宣称所有任务都能等比例提速。

MT 普通终端内，Debian 显式设置 `http_proxy` 和 `https_proxy` 为本地 7890 后，Claude 安装开始进入 Setting up 阶段。实际验证 Android shell 的 PDN Alpine 可以继承代理变量并访问 Claude 下载链路。安装器在输出 Setting up 前静默下载程序，当时 ARM64 文件约 244 MiB；该下载等待不是 PRoot 执行慢的独立证据。没有修改代理相关代码，不记录账号或认证信息。

## 已定位的修改点

1. `src/proot/src/tracee/event.c`，`launch_tracee()` 子进程在 SIGSTOP 后的原始过滤器安装调用已被移除，仅保留禁用说明。当前设置环境变量不能恢复加速。
2. 同一文件 `handle_tracee_event()`，当 `restart_how == 0` 时强制使用 `PTRACE_SYSCALL`。上游逻辑应为：

```c
tracee->restart_how =
    tracee->seccomp == ENABLED && !sysexit_necessary
    ? PTRACE_CONT : PTRACE_SYSCALL;
```

3. `src/proot/src/cli/pdn.c` 登录组装强制补上 `PROOT_NO_SECCOMP=1`，需取消这一全局强制行为，再由引擎做安全选择。
4. `android/proot-engine/src/main/java/id/or/oo/pr/engine/PdnRuntime.kt` 也固定提供 `PROOT_NO_SECCOMP=1`；本阶段先不修改，AAR 继续原有兼容策略。
5. 上游 `vendor/termux-proot/src/tracee/event.c` 在变量不存在时调用 `enable_syscall_filtering(tracee)`。vendor 只读。

`PROOT_NO_SECCOMP` 的传统语义是变量存在即关闭，设成 0 也不表示开启。

## 本阶段采用的保守边界

- 默认只在宿主没有继承 seccomp 过滤器时尝试开启 PRoot 加速；宿主模式可在 tracer 父进程中通过 `prctl(PR_GET_SECCOMP, ...)` 查询。
- 宿主模式为 2、查询失败或显式设置 `PROOT_NO_SECCOMP` 时继续完整 syscall 追踪。
- 恢复过滤器安装与条件式 PTRACE_CONT 两处，不能只改环境变量。
- 保留全部 Android SIGSYS、原始寄存器、loader 和 fake root 兼容逻辑。
- 这个第一阶段主要针对允许安全开启的 Shell 环境。MT 普通 App 不承诺同样获得加速；需要之后独立解决继承 seccomp 的兼容问题。

### 普通 App 的具体风险

`tracee/seccomp.c` 的 SIGSYS 处理保存 `ORIGINAL_SECCOMP_REWRITE`，但部分 chdir/fchdir/getcwd/linkat/clone 等处理读 `ORIGINAL` 的第一参数。完整追踪会在 syscall entry 捕获 ORIGINAL；加速后，继承过滤器的 TRAP 可能绕过 PRoot 的 TRACE 事件，导致 ORIGINAL 来自旧调用。这个风险尚未实机复现/修复，不能直接全局启用加速。

现有 `tests/test_proot_nolib.py` 的 SIGSYS getpid 用例默认禁用加速，不能算作混合过滤器加速验收。

## 恢复后的步骤

- [ ] 先核对 Git 状态；忽略已有 vendor/bionic 和 vendor/termlib 的基线脏状态，不添加无关配置/认证/缓存文件。
- [ ] 按 Superpowers 流程保存实施计划，再修改原始 ELF 的加速策略；保持 AAR 源码不动。
- [ ] 增加禁用变量、无继承过滤器、已有过滤器和查询失败的策略验证；真实 Shell 验证加速日志及 guest 行为。
- [ ] 新建隔离的测试目录，重编译原始 ELF，并验证 host Seccomp 0/2 两种路径、正常/信号退出、SIGSYS、fork/exec、路径/身份及 loader 回归。
- [ ] 测量同一候选 ELF 的加速/禁用对照，计时排除 rish 建连和下载/安装；优先 tar、C 编译与文件任务。
- [ ] 新增/修改行覆盖率至少 80%；独立规格及质量评审通过后交付。
- [ ] 如果形成正式源码修改，默认 patch 升至 0.6.7，同步 `proot.h`、安装 User-Agent、版本测试和当前源码文档。不要把仍为 0.6.6 的既有 AAR 改名冒充新版。
- [ ] 更新 `/sdcard/yyd/PDN` 的原始 ELF 与配套 loader、.so 副本及校验值；按要求暂不本机构建 AAR/APK，不擅自发布新 Release。
- [ ] 测试完成后清理新临时 rootfs/编译和采集缓存，保留最终产物及必要报告；提交并推送。

## 可复用入口

原生构建：

```sh
NDK_PATH=/你的/NDK目录 CC=clang sh scripts/build-proot-nolib.sh
```

默认产物：`build/proot-distro-nolib/arm64/pdn`、`proot-loader` 与 `jniLibs/arm64-v8a/`。

本机已存在 NDK 26.3.11579264 与 Termux Clang、llvm-profdata/llvm-cov、Gradle、aapt2、apksigner。依赖构建缓存和 `build/releases/v0.6.6` 已保留。Alpine 官方归档在 `build/pdn-sources/alpine-minirootfs-3.24.2-aarch64.tar.gz`。

当前待办见 `docs/pdn-workspace-and-local-update.md`，已交付测试方法见 `docs/pdn-error-testing.md`。恢复时不重复 R8 或代理诊断，直接继续原始 ELF seccomp 阶段。
