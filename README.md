# proot-distro-nolib

简体中文 | [English](README.en.md)

**ARM64 Android**。当前版本见 [GitHub Releases](https://github.com/EMERLADD/proot-distro-nolib/releases)，功能更新见 [CHANGELOG](CHANGELOG.md)。

面向 ARM64 Android 的免 root Linux 发行版管理器与可嵌入 SDK。无需 Termux，即可运行 Debian、Ubuntu、Alpine 和 Arch。

[![Maven Central](https://img.shields.io/badge/Maven_Central-0.6.18-0969da)](https://central.sonatype.com/artifact/io.github.emerladd/pdn-engine/0.6.18)

## 快速开始

[![调试 Android](https://img.shields.io/badge/%E8%B0%83%E8%AF%95%20Android-6f42c1?style=for-the-badge)](docs/pdn-shizuku-android-shell.md)
[![集成到我的 App](https://img.shields.io/badge/%E9%9B%86%E6%88%90%E5%88%B0%E6%88%91%E7%9A%84%20App-0969da?style=for-the-badge)](docs/android-embedding.md)
[![在 Shell 中使用](https://img.shields.io/badge/%E5%9C%A8%20Shell%20%E4%B8%AD%E4%BD%BF%E7%94%A8-238636?style=for-the-badge)](docs/pdn-shell.md)

## 目录

GitHub 安卓 App 的页内目录跳转可能不可用；可通过上方按钮打开独立教程，或在浏览器中查看本页。

- [0. 这是什么？](#0-这是什么)
- [1. 运行条件与权限](#1-运行条件与权限)
- [2. 下载与更新](#2-下载与更新)
- [3. 快速开始](#3-快速开始)
- [4. 已验证的使用场景](#4-已验证的使用场景)
- [5. 文档导航](#5-文档导航)
- [6. 构建与贡献](#6-构建与贡献)
- [7. 来源与许可证](#7-来源与许可证)
- [8. 常见问题](#8-常见问题)
- [9. 现有局限](#9-现有局限)

## 0. 这是什么？

**PDN 是基于 [pr](https://github.com/oonid/pr)、面向 ARM64 Android 的免 root Linux 发行版管理器与可嵌入 SDK。** 它把 PRoot 引擎、下载、校验、解压和发行版管理整合进一个 ARM64 Android 程序，让 Android 设备运行 Linux 环境，无需额外安装 Termux，也不需要宿主提供 Bash、Python、curl 或 tar。

<p align="center">
  <img src="docs/images/mt-ubuntu-highlighted.png" alt="MT 管理器内置终端通过 PDN 进入 Ubuntu，黄色框标出 MT 私有目录" width="420">
</p>

**实机演示：从 MT 管理器进入 Ubuntu。** 图中使用 MT 管理器内置终端模拟器，宿主是普通 App 权限下的 Android Shell；黄色框标出了 MT 私有目录 `/data/user/0/bin.mt.plus/files/term/home`。执行 `pdn login Ubuntu` 后进入 Ubuntu 24.04 LTS（ARM64）。Linux 中的 `root` 是 PRoot 模拟身份，不代表 Android 已取得 root；这条运行路径不需要 Termux 或 Shizuku。截图保留当时的版本号，见[未经标注的原图](docs/images/mt-ubuntu-original.jpg)。

- **直接管理 Linux**：安装、登录、执行命令、挂载项目目录、保存配置、备份恢复和卸载。
- **可供其他 APK 调用**：提供 AAR 和直接打包 `.so` 两种接入方式，适合工作区、AI 前端或其他需要 Linux 执行能力的 App。
- **可接入 GUI 和终端**：Java/Kotlin API 提供结构化事件、错误建议、异步任务和独立 PTY 终端会话，界面由宿主绘制。
- **路径由宿主决定**：程序、数据、缓存和项目目录由参数或宿主提供，不绑定 MT 管理器、Termux 或固定 App 包名。
- **条件允许时可调试 Android**：从 Shizuku/rish 或 ADB shell 启动 PDN 后，Linux 内可调用具有继承权限的 Android 调试命令，例如 `cmd`、`settings`、`getprop`。

以上主要链路已经通过 **MT 管理器、AAR 构建的独立 APK、直接打包 `.so` 的独立 APK** 验证；功能结果见[验证情况](#4-已验证的使用场景)。Android 调试路径验证的是 ADB shell 级权限下的系统命令，不是 Android 真 root，也不代表已验证 Linux 内独立 adb 客户端的全部功能。

| 接入方式 | 适用情况 |
| --- | --- |
| 原始 ELF（`pdn`） | 终端工具、Shell 环境、MT 管理器 |
| AAR | Kotlin / Java Android App，希望使用封装好的 API |
| 直接打包 `.so` | 希望自行管理进程、事件和 PTY 的 Android App |

`nolib` 表示不依赖 Termux 的动态库。PDN 仍使用 Android 系统 `libc.so`、`libdl.so`，下载、TLS 和解压组件静态链接；AAR 接入需要 Kotlin 标准库：Maven 依赖自动传递，本地 AAR 接入需手动添加。

## 1. 运行条件与权限

- **架构与版本**：目前提供 ARM64 / AArch64。独立 ELF 构建目标为 Android API 24；AAR/JNI 接入最低 API 28。
- **执行与进程跟踪**：宿主必须允许执行程序、PRoot 的进程跟踪和相关系统调用。普通 App 可按教程把程序部署到 `nativeLibraryDir`。
- **Linux 存储**：目录必须可访问并支持 Unix 权限和符号链接，例如 App 私有目录或 Android shell 的 `/data/local/tmp`。`/sdcard` 适合下载包、备份和共享文件，不适合解压 rootfs。
- **网络与共享文件**：在线安装需要网络权限，读取共享存储需要相应访问权限。

PRoot 的 root 是模拟身份，宿主的真实 UID 和 SELinux 限制仍然有效。Shizuku 是可选的权限入口，安装和运行 Linux 本身不要求它。部署步骤见 [Shell 部署教程](docs/pdn-shell.md)和 [App 接入教程](docs/android-embedding.md)。

## 2. 下载与更新

项目目前处于测试阶段。

从 [Releases](https://github.com/EMERLADD/proot-distro-nolib/releases) 获取同一版本的文件，并核对 `SHA256SUMS`：

| 场景 | 文件 |
| --- | --- |
| Android shell / MT 终端 | `pdn`，需要外部 loader 时配套 `proot-loader` |
| Java/Kotlin App 接入 | `pdn-engine-版本号.aar` |
| App 自行启动进程 | `libpdn.so`、`libproot-loader.so` |
| 源码与许可材料 | `proot-distro-nolib-v版本号-android-arm64.tar.gz` |
| 独立 App 接入验证 | `pdn-aar-probe-版本号-*-test.apk`、`pdn-so-probe-版本号-*-test.apk` |

两种测试 App 各提供 Debug 与 R8 Release，用于演示 AAR / 直接 `.so` 接入，均使用测试签名，不作为生产应用。

AAR 包含 Java/Kotlin 接口，以及 PDN、配套加载器和 PTY JNI 三个原生文件，保留全部终端会话接口。终端界面由宿主 App 提供。标准版与 lite 文件内容相同，任选一个导入即可。PDN 和加载器使用 `.so` 文件名供 APK 打包，但仍通过进程方式调用；`libptyjni.so` 则通过 JNI 调用。

更新前退出旧 Linux 会话，替换程序并重新设置执行权限；已有 rootfs 不需要重装。ELF、`.so`、AAR 和 loader 应保持同一版本，`pdn version` 和 `pdn --version` 都应显示当前 PDN 版本。转发二进制时同时保留对应源码和许可证。

文件结构和更新注意事项见[构建与发布](docs/pdn-build-and-release.md)。

## 3. 快速开始

### 3.1 在终端中部署 ELF

从 [MT 管理器终端部署教程](docs/pdn-shell.md)开始：下载 ELF、复制到 App 私有目录、设置固定存储路径并注册 `pdn` 短命令。以 MT 普通终端为例，运行 Linux 本身无需 Shizuku 或 Termux；其他具备执行权限的 Android shell 可换用自己的目录。

### 3.2 安装和管理发行版

部署后可安装、登录和执行 Linux 命令。来源、续传、多实例、挂载、备份恢复和卸载见 [发行版管理教程](docs/pdn-distributions.md)，完整命令见 [CLI 手册](docs/proot-distro-nolib.zh-CN.md)。

### 3.3 在 Linux 内调用 Android 调试命令

需要 Android shell 级权限时，从 Shizuku/rish 或 ADB shell 启动，再配置 Android 系统挂载和 Linux 内的命令入口。完整流程及前台连接要求见 [调试 Android](docs/pdn-shizuku-android-shell.md)。

### 3.4 通过 AAR 接入 App

1. 在 `settings.gradle.kts` 的依赖仓库中启用 `mavenCentral()`，再添加下面的依赖；Kotlin 标准库会自动传递。也可下载本地 AAR，并手动提供标准库。
2. 配置网络权限和原生库解压，从宿主提供程序、数据、缓存、项目目录。
3. 通过 `PdnRuntime` 生成操作，再用 `PdnOperations` 启动任务、接收事件和结果。
4. 需要交互终端时使用 `PdnTerminal`，由 App 绘制界面并处理生命周期。

```kotlin
dependencies {
    implementation("io.github.emerladd:pdn-engine:0.6.18")
}
```

普通任务需要输入时，使用 `startInteractive()`、`task.writeStdin()` 和 `task.closeStdin()`，详见 [stdin 接口](docs/pdn-aar-api.md#普通任务输入stdin)。默认任务仍关闭输入。

```java
PdnRuntime pdn = new PdnRuntime(host);
PdnOperations operations = new PdnOperations(pdn);
PdnTask task = operations.start(pdn.install("alpine"), listener);
```

`host` 实现 `ProotHost`，`listener` 实现 `PdnListener`；`install()` 自身返回未启动的 `ProcessBuilder`。完整打包见 [App 接入教程](docs/android-embedding.md)，任务、查询、配置和终端接口见 [AAR API](docs/pdn-aar-api.md)，可运行工程见 [AAR 示例](examples/aar-probe/README.md)。

### 3.5 直接打包 `.so` 接入 App

1. 将同版本 `libpdn.so` 和 `libproot-loader.so` 放入 `app/src/main/jniLibs/arm64-v8a/`。
2. 配置原生库解压，通过 `context.applicationInfo.nativeLibraryDir` 获取程序位置。
3. 使用进程参数设置 rootfs、缓存和挂载目录，通过 `ProcessBuilder` 或自己的 PTY 层启动。
4. 从独立事件通道读取结构化结果，保留 stdout/stderr 用于 Linux 命令输出。

这些 ELF 不使用 `System.loadLibrary("pdn")` 调用。目录、环境和事件协议见 [App 接入教程](docs/android-embedding.md)及[事件协议](docs/pdn-events.md)，完整工程见 [.so 示例](examples/so-probe/README.md)。

## 4. 已验证的使用场景

| 场景 | 验证结果 |
| --- | --- |
| Maven Central 接入 | Java/Kotlin 依赖可解析；Debug 与 R8 Release App 的安装、命令、事件、stdin 和 PTY 均验证通过 |
| MT 管理器 / Android shell | 可安装和启动 Linux；Ubuntu 内可调用继承 shell 权限的 Android 调试命令 |
| AAR 独立 APK | Debug 与 R8 Release 均通过初始化、安装、命令、事件、工作区、异步任务和交互终端验收 |
| 直接 `.so` 独立 APK | Debug 与 R8 Release 均通过独立进程、事件和 PTY 接入验收 |
| GUI 安装 Linux 软件 | Alpine 内安装 nano、curl，以及 HTTPS 访问通过 |

两种 APK 路径均可在普通 Android App 身份下运行；Linux 内的模拟 root 不改变宿主权限。

## 5. 文档导航

| 我想做什么 | 文档 |
| --- | --- |
| 从 Linux 调用 Android 系统调试命令 | [调试 Android](docs/pdn-shizuku-android-shell.md) |
| 安装、登录、挂载、配置、备份或卸载 Linux | [发行版管理教程](docs/pdn-distributions.md) |
| 给其他 APK 嵌入 PDN | [App 接入教程](docs/android-embedding.md) |
| 在 MT 等终端部署原始 ELF | [Shell 部署教程](docs/pdn-shell.md) |
| 查 Java/Kotlin 配置、任务、查询和终端接口 | [AAR API](docs/pdn-aar-api.md) |
| 自己接事件或排查分类错误 | [事件协议](docs/pdn-events.md)、[错误说明](docs/pdn-errors.md) |
| 查上游归档与 SHA256 | [rootfs 来源](docs/pdn-rootfs-sources.zh-CN.md) |
| 自行构建与源码分发 | [构建与发布](docs/pdn-build-and-release.md) |
| 排查目录、版本、运行权限和终端问题 | [常见问题](docs/pdn-faq.md) |
| 查完整 CLI 细节 | [原生 CLI 手册](docs/proot-distro-nolib.zh-CN.md) |

## 6. 构建与贡献

独立 PDN 构建不需要 Java、Gradle 或 Rust，AAR 则需 Android/Gradle 工具链。构建依赖、NDK 配置、原生与 AAR 产物和许可证交付见[构建与发布](docs/pdn-build-and-release.md)。

欢迎通过 [Issues](https://github.com/EMERLADD/proot-distro-nolib/issues) 提交问题、想法或文档改进，附上宿主 App、Android 版本、`pdn version`、复现命令和错误输出。不要包含密码、Token、设备序列号或其他隐私信息。变更记录见 [CHANGELOG](CHANGELOG.md)。

## 7. 来源与许可证

项目基于 [oonid/pr](https://github.com/oonid/pr)，保留其 Android PRoot 适配和原作者信息。引擎来自 [PRoot](https://github.com/proot-me/proot) / [Termux PRoot](https://github.com/termux/proot)，使用方式参考 [proot-distro](https://github.com/termux/proot-distro)。

构建使用 [talloc / Samba](https://www.samba.org/)、[curl](https://curl.se/)、[Mbed TLS](https://github.com/Mbed-TLS/mbedtls)、[libarchive](https://www.libarchive.org/) 和 [zlib](https://zlib.net/)。引擎沿用 GPL-2.0-or-later，talloc 为 LGPL-3.0-or-later；各组件范围见 [LICENSE](LICENSE) 和发布包中的许可材料，不能统一声明为 MIT。

转发二进制时同时保留对应源码、第三方许可证和构建信息。详见[发布材料](docs/pdn-build-and-release.md)。

## 8. 常见问题

| 问题 | 快速检查 |
| --- | --- |
| rootfs 或临时目录不可访问 | 检查真实身份、显式目录变量、目录存在性和权限。 |
| 更新后仍显示旧版本 | 用完整路径运行，核对 PATH、已复制文件和 APK 内嵌副本。 |
| Ubuntu 内找不到 rish 或一直出现 `>` | 先定义 Bash 函数；Ctrl+C 取消未结束的输入，完整复制代码块。 |
| Shizuku/rish 断连 | 宿主保持前台，检查授权和服务；重启后重新建立会话。 |
| App 中不能执行 `.so` | 检查 `nativeLibraryDir`、原生库解压和匹配 loader。 |
| 命令执行失败但没抛 Java 异常 | 检查 `PdnResult.isSuccess()`、错误码、原因和建议。 |

具体命令和排查路径见[常见问题](docs/pdn-faq.md)。

## 9. 现有局限

- **实例迁移的范围**：clone 沿用备份复制规则；rename 迁移 rootfs 内的宿主绝对链接和保存的 bind，普通文件内容与环境变量中的路径不自动重写。SIGINT/SIGTERM 可回滚，SIGKILL/掉电恢复尚未实现。

- **运行平台**：当前提供 ARM64 Android；不提供跨架构模拟，也不能保证所有 ROM、App 或权限环境可运行。
- **权限和隔离**：PRoot 不提供真实 root，也不是安全隔离边界。Shizuku/shell 权限同样受 Android 限制；Linux 内的独立 adb 客户端不在这里描述的调试链路支持范围。
- **系统调用兼容性**：原始 ELF 根据宿主已有的 seccomp 过滤器及查询结果选择追踪路径；已有过滤器、查询失败或显式禁用时使用完整追踪。普通 App 与 AAR 使用兼容策略。
- **发行版来源**：只支持内置的固定归档；离线安装也需要匹配校验值。暂不支持通用 OCI/Docker 镜像导入和自动镜像测速。
- **界面与后台**：AAR 提供终端会话能力，不附带终端渲染控件或完整桌面；没有自动后台会话服务。使用 Shizuku 入口时应保持宿主前台。
- **发布与更新**：设备本地自动拉源码、打补丁并更新 PRoot 的高级功能尚未实现。
- **旧 pr 接口**：AAR 不包含旧 pr 原生组件；保留的兼容类中，依赖旧 pr-cli 的方法不能只靠 PDN AAR 运行。
