# 独立 PDN AAR 验证 App

这是独立 Gradle 项目，包名 `org.example.pdnprobe`。`settings.gradle.kts` 只包含 `:app`，不引用原仓库源码模块、termlib 或原 App 的数据。界面和自动验收使用 Java 与 Android 平台控件。

正常 APK 的依赖是本地 `pdn-engine.aar` 和 Kotlin 标准库。AAR 内的 Kotlin 类需要标准库；不要求 Kotlin 插件、协程或 Compose。安装按钮从官方 ARM64 源下载；完整自动验收使用打包到 assets 的官方 Alpine 3.24.2 ARM64 归档，在运行时解压安装，不预置已解压的 rootfs。

## 实测结果

2026-10-08，生成的 AAR 在这个全新独立 App 中实测可用：进入 Alpine 后，通过交互终端成功执行 `apk add nano`。正常 APK 的签名、Manifest 和与 AAR 对应的原生文件哈希均已检查。

2026-10-09，使用 GitHub Release 0.6.4 AAR，在 Android 14（SDK 34）的普通 `untrusted_app` 进程中完成自动验收：**19/19 通过**。界面初始化、官方源在线安装、执行、终端输入、Ctrl-C、resize 和关闭均通过；完整验收从内置官方归档安装新 Alpine，验证启动错误与事件回调。Java 验证代码实机行覆盖率为 425/454（93.61%）。APK 的 minSdk 28、targetSdk 35，包内 PDN 与 loader 和 Release 文件逐字节一致。

2026-10-09，0.6.5 轻量 AAR 的扩展验收 **33/33 通过**：新增配置、结构化查询、异步独立任务、真实取消与超时进程树清理、双终端、退出状态、启动错误回调和 JNI 边界检查。APK 仅含 PDN、loader 和 PTY JNI 三个原生文件；实机 JNI 行覆盖率 269/292（92.12%）。SDK 全部类的单元与实机合并行覆盖率 944/1044（90.42%）。

## 构建

先执行 `prepare.sh`，将 Release 0.6.6 PDN AAR 放入 `app/libs/pdn-engine.aar`，并准备官方 Alpine 归档供验收使用。脚本可指定 Release 目录和归档路径；只导入 AAR 可以构建，但完整验收还需要归档 asset。然后在本目录执行：

```sh
./prepare.sh
./gradlew :app:assembleDebug
```

Termux 内构建时，指定可运行的 aapt2：

```sh
./gradlew -Pandroid.aapt2FromMavenOverride="$(command -v aapt2)" :app:assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。minSdk 28、targetSdk 35，当前只含 `arm64-v8a`。Manifest 保留网络权限与 `extractNativeLibs=true`，程序从 Android 解压的 `nativeLibraryDir` 启动。

## 界面与验收

- 初始化：准备本 App 私有目录，调用 PDN 版本 API 与事件监听器。
- 安装 Alpine：调用官方源安装；不覆盖已有系统。
- 执行命令：通过 `exec()` 执行输入框中的 shell 命令，分别读取 stdout/stderr 和最终结果。
- 打开终端：通过 AAR 的 PTY JNI 登录 Alpine，支持连续命令输入、Ctrl-C、32×96 resize 和关闭。
- 全部验收：每次创建新的测试 rootfs 目录，通过内置官方归档校验、安装 Alpine；验证参数含空格、模拟 root、工作目录挂载、项目持久化、事件顺序/关联/唯一最终结果/回调线程、guest 非零和信号退出、shell 与 loader 启动错误分类及建议和 PTY 正常退出。

终端是简易 PTY 输入输出面板。它保留跨分块 UTF-8 解码状态；完整终端屏幕渲染可由宿主另行接入。

自动验收可从已授权的 shell 运行，代码在普通 App 进程里执行：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am instrument -w org.example.pdnprobe/.ProbeInstrumentation
adb shell run-as org.example.pdnprobe cat files/acceptance.json
```

已有 rish/Shizuku 环境也可执行：

```sh
rish -c 'am instrument -w org.example.pdnprobe/.ProbeInstrumentation'
rish -c 'run-as org.example.pdnprobe cat files/acceptance.json'
```

本次环境中，单独启动 instrumentation 后界面没有进入前台，显式打开 Activity 后才继续验收。遇到相同情况，在 Android shell 中执行：

```sh
am instrument -w org.example.pdnprobe/.ProbeInstrumentation &
probe_test_pid=$!
sleep 2
am start -W -n org.example.pdnprobe/.MainActivity
wait "$probe_test_pid"
run-as org.example.pdnprobe cat files/acceptance.json
```

`run-as` 只用于读取报告，Linux 操作仍在普通 App 进程中执行。导出报告到共享存储时使用 `run-as ... cat ... | cat > 输出文件`，避免 App 进程直接写入宿主打开的共享文件描述符。

自动验收实际点击初始化、安装、执行、终端输入/控制/resize/关闭及全部验收按钮。已有手动安装时，安装按钮会验证拒绝覆盖；完整验收仍使用全新目录完成真实安装。

## 覆盖率验收版本

```sh
./gradlew -PprobeCoverage=true :app:assembleDebug
```

这个可选版本加入 JaCoCo 测试运行库，验收结束后写入 `files/coverage.ec`。正常构建不包含它。测试结果以 `acceptance.json` 的 `passed` 和每项检查为准；签名、Manifest、AAR 原生文件哈希也必须通过校验。

AAR 校验值见 [AAR.sha256](AAR.sha256)。本地 AAR 文件不纳入 Git；单独复制此项目后放入同一 AAR 即可构建，完整验收还需准备归档 asset。

归档 asset 使用 `alpine-rootfs.archive` 文件名保存原始 gzip 字节；`.gz` 后缀会被当前构建工具自动解压并改名。PDN 从内容识别压缩格式，并按内置大小和 SHA256 校验。验收使用 `NOFOLLOW_LINKS` 检查 `/bin/sh` 入口，因为 Alpine 的绝对符号链接指向 guest 的 `/bin/busybox`，Android 宿主不能按自己的 `/bin` 判断它是否存在。
