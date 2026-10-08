# 独立 PDN AAR 验证 App

这是独立 Gradle 项目，包名 `org.example.pdnprobe`。`settings.gradle.kts` 只包含 `:app`，不引用原仓库源码模块、termlib 或原 App 的数据。界面和自动验收使用 Java 与 Android 平台控件。

正常 APK 的依赖是本地 `pdn-engine.aar` 和 Kotlin 标准库。AAR 内的 Kotlin 类需要标准库；不要求 Kotlin 插件、协程或 Compose。Linux rootfs 在运行时从官方 ARM64 源下载，不预置到 APK。

## 实测结果

2026-10-08，生成的 AAR 在这个全新独立 App 中实测可用：进入 Alpine 后，通过交互终端成功执行 `apk add nano`。正常 APK 的签名、Manifest 和与 AAR 对应的原生文件哈希均已检查。

自动验收与实机覆盖率报告尚未采集；下面提供运行方式。该手动结果确认独立 AAR 接入与终端软件安装链路可用。

## 构建

先将生成或下载的引擎 AAR 放入 `app/libs/pdn-engine.aar`，然后在本目录执行：

```sh
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
- 全部验收：每次创建新的测试 rootfs 目录，实际下载、校验、安装 Alpine；验证参数含空格、模拟 root、工作目录挂载、项目持久化、事件顺序/关联/回调线程、guest 非零退出、启动错误建议和 PTY 正常退出。

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

自动验收实际点击初始化、安装、执行、终端输入/控制/resize/关闭及全部验收按钮。已有手动安装时，安装按钮会验证拒绝覆盖；完整验收仍使用全新目录完成真实安装。

## 覆盖率验收版本

```sh
./gradlew -PprobeCoverage=true :app:assembleDebug
```

这个可选版本加入 JaCoCo 测试运行库，验收结束后写入 `files/coverage.ec`。正常构建不包含它。测试结果以 `acceptance.json` 的 `passed` 和每项检查为准；签名、Manifest、AAR 原生文件哈希也必须通过校验。

AAR 校验值见 [AAR.sha256](AAR.sha256)。本地 AAR 文件不纳入 Git；单独复制此项目后放入同一 AAR 即可构建。
