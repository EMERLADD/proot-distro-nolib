# PDN Release/R8 验收 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement and review each bounded task.

**Goal:** 更新 W/U 待办，并验证现有 AAR 与直接 .so 在开启 R8 的 Release 测试 App 中运行。

**Architecture:** 复用独立 probe 的自动验收与 Release 0.6.6 原件。Release 开启 minify 和 shrinkResources，使用本地测试签名，保持非 debuggable；通过 instrumentation 输出读取结果，不依赖 run-as。先验证现有 SDK，只有证据表明需要时才添加 consumer 规则。

**Tech Stack:** Java 17, AGP 8.7.3, R8, Kotlin 标准库, Android instrumentation, PRoot/PTY JNI.

## Task 1: 当前待办

Files: README.md, README.en.md. The former task catalogue was later removed.

- [x] 核对当前 API、原生能力和验收记录，建立当前状态表；保留原规划为历史背景。
- [x] 已完成项包括 W1、W2、W3/W4 的异步任务、取消/超时及独立多终端；剩余项独立列出，不把宿主生命周期服务算作已实现。
- [x] 当前优先级为 Release/R8、执行性能、多实例、异常中断恢复、FD/流、实际工作流、设备更新。

## Task 2: 混淆测试构建

Files: examples/aar-probe/app/build.gradle.kts, examples/so-probe/app/build.gradle.kts, examples/aar-probe/README.md, examples/so-probe/README.md.

- [x] 两个 probe 的 Release 配置添加 isMinifyEnabled=true、isShrinkResources=true、默认 optimize 规则，测试签名使用 debug signingConfig。
- [x] Probe App versionName 增加 patch，versionCode 增加 1；PDN 原件保持 0.6.6。
- [x] 不添加宽泛 keep 整个 SDK 的规则；不添加测试覆盖率运行库。
- [x] 构建命令：在各 probe 根目录执行 `./gradlew -Pandroid.aapt2FromMavenOverride="$(command -v aapt2)" :app:assembleRelease`。
- [x] 保存 R8 mapping、usage、configuration，证明 SDK 类确实被重命名或优化。

## Task 3: 实机与交付

Files: docs/pdn-error-testing.md, docs/pdn-aar-api.md, README.md, README.en.md.

- [x] 使用 Android shell 安装两份 Release 测试 App，通过 `am instrument -w -r 包名/.ProbeInstrumentation` 与显式 Activity 前台启动运行完整验收。
- [x] 从 instrumentation 输出保存 JSON 报告，要求 AAR 47 项和直接 .so 的原 38 项及新增退出状态回归通过，包含真实 JNI/PTY、多会话、事件和路径测试。
- [x] 校验 APK ZIP、签名、Manifest 非 debuggable/SDK、三项原生文件与 AAR/ELF 原件逐字节一致。
- [x] 保持已有 SDK 行覆盖率超过 80%；混淆构建仅修改配置，无新增运行时代码则不虚报新的行覆盖率。
- [x] 两阶段独立评审通过后更新记录、待办、本地 /sdcard/yyd/PDN 产物和文档，提交并推送。

## 验收发现与修复

直接 .so 示例在非 debuggable Release 中无法通过 `/proc/<pid>` 存在性检查，导致 GUI 终端验收失败。示例旧 waitPid 同时用 0 表示运行中和成功退出，因此依赖这项检查。改为 waitpid 明确报告运行中 -2、失败 -1、已退出非负状态，重试 EINTR；不通过 /proc 判断会话状态。AAR 已有独立等待状态，不需修改。

- [x] 修正 examples/so-probe/native/probepty.c、NativePty/ProbeTerminal；新增实际运行中、退出 0/37、信号退出和已回收状态验证。
- [x] 新示例 Java/JNI 的 Debug 覆盖率至少 80%，再交付无插桩 Release/R8 APK。

Validation: AAR Release 47/47；直接 .so Release 39/39，Debug 覆盖率验收 39/39。示例 Java 95.08%、JNI 89.52%；实际 R8 重命名、非 debuggable、ZIP/签名、原生字节一致性均通过。规格与质量评审通过。
