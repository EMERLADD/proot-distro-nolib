# PDN AAR 示例 App

简体中文 | [English](README.en.md) · [返回 README](../../README.md)

独立 Java Android 工程，包名 `org.example.pdnprobe`，minSdk 28、targetSdk 35，仅支持 ARM64。依赖本地 PDN AAR 和 Kotlin 标准库，无需原仓库模块、Kotlin 插件或 Compose。

这是普通演示和测试 App，不是生产应用。Debug 与 R8 Release 使用测试签名。终端为简易 PTY 输入输出面板，不是完整终端渲染控件。

## 构建

先准备与当前版本匹配的 Release 文件。`prepare.sh` 准备引擎和演示所用官方 Alpine 归档，并编译文件操作探针，需要 Android NDK、C 编译器及 Python 3；该脚本可能联网下载文件。

```sh
./prepare.sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

在 Termux 构建时指定可运行的 aapt2：

```sh
./gradlew -Pandroid.aapt2FromMavenOverride="$(command -v aapt2)" :app:assembleDebug
```

输出位于 `app/build/outputs/apk/debug/` 和 `app/build/outputs/apk/release/`。Release 启用 R8 混淆、优化和资源压缩，使用默认优化/JNI 规则。正式分发自己的 App 时应改用自己的包名和签名。

## 使用

界面提供初始化、安装 Alpine、执行命令、打开终端、Ctrl-C、resize 和关闭。安装按钮需要网络且不会覆盖已有系统；命令输出与最终错误结果分别显示。“全部验收”会创建独立临时实例，部分检查需要联网。

自己的 App 接入见 [App 接入教程](../../docs/android-embedding.md)与 [AAR API](../../docs/pdn-aar-api.md)。
