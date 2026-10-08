# 直接打包 .so 的 PDN 验证 App

独立 Java 工程，包名 `org.example.pdnsoleprobe`，minSdk 28、targetSdk 35。Gradle 只包含 `:app`，没有 AAR、Kotlin、引擎源码模块或原 App 依赖。`NativeRuntime` 使用宿主提供的私有目录和环境，通过 ProcessBuilder 启动 nativeLibraryDir 中的 Release 0.6.4 ELF；`NativeOperations` 读取 JSONL 并交付回调。

2026-10-09，在 Android 14（SDK 34）的普通 `untrusted_app` 进程中验收 **24/24 通过**，包括界面官方源在线安装、exec、事件、PTY、启动错误及读取器异常。Java 实机行覆盖率为 569/593（95.95%），自有 PTY JNI 为 92/103（89.32%）。详细记录见 [APK 接入实测](../../docs/pdn-error-testing.md#064-apk-接入实测)。

## 构建

准备 [Release 0.6.4](https://github.com/EMERLADD/proot-distro-nolib/releases/tag/v0.6.4) 的 `libpdn.so`、`libproot-loader.so`，以及官方 Alpine 3.24.2 ARM64 归档，然后执行：

```sh
./prepare.sh /你的/Release目录 /你的/alpine-minirootfs-3.24.2-aarch64.tar.gz
./gradlew --offline -Pandroid.aapt2FromMavenOverride="$(command -v aapt2)" :app:assembleDebug
```

脚本未指定参数时使用仓库的 `build/releases/v0.6.4` 和 `build/pdn-sources`。SDK 使用 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT`，NDK 可通过 `PDN_NDK_DIR` 指定，默认 26.3.11579264。Termux 使用可运行的 Clang 搭配 NDK sysroot；Linux 主机默认使用 NDK Clang。首次获取 Gradle 依赖时去掉 `--offline`。

原生文件放在 `app/src/main/jniLibs/arm64-v8a/`，归档放在 assets；这些输入不纳入 Git。归档以 `alpine-rootfs.archive` 命名，保留原始 gzip 字节，避免构建工具自动解压 `.gz` asset。PDN 按内置大小和 SHA256 校验再安装。

脚本还编译这个工程自有的 `libprobepty.so`，负责 PTY、输入输出、resize 和子进程回收。它只依赖 Android libc/libdl，16 KiB 对齐。`libpdn.so` 与 loader 是原样打包的可执行 ELF，通过进程运行；只有自有 PTY 适配器通过 System.loadLibrary 加载。

## 运行验收

界面可以初始化、安装 Alpine、执行命令、打开终端、连续发送输入、Ctrl-C、调整尺寸和关闭。安装按钮使用官方在线源；完整验收使用内置官方归档，每次创建新 rootfs，错误 fixture 使用独立目录。

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am instrument -w org.example.pdnsoleprobe/.ProbeInstrumentation
adb shell run-as org.example.pdnsoleprobe cat files/acceptance.json
```

rish 中执行相同的 Android shell 命令即可。若 instrumentation 启动后界面未进入前台，执行：

```sh
am instrument -w org.example.pdnsoleprobe/.ProbeInstrumentation &
probe_test_pid=$!
sleep 2
am start -W -n org.example.pdnsoleprobe/.MainActivity
wait "$probe_test_pid"
run-as org.example.pdnsoleprobe cat files/acceptance.json
```

`run-as` 仅导出报告；验收要求实际操作进程是 `untrusted_app`。导出到共享存储使用 `run-as ... cat ... | cat > 输出文件`。报告保留逐项结果和 instrumentation 失败原因，`passed=true` 且最终 `INSTRUMENTATION_CODE: -1` 才通过。JSONL 与最近一次 stderr 保存在私有 cache/engine 下。

验收核对版本、安装阶段、精确 argv、stdout/stderr、工作区写入、假 root、真实 TTY、32×96 resize 和正常退出；事件检查关联 ID、连续 sequence、唯一 started/result/error。还触发初始 shell、实际登录 shell 和 loader 的启动失败，验证 errno 与建议，并确认 guest 退出 17/127/SIGTERM 没有误报。App 数据目录中的坏 loader 实际返回 EACCES，不宣称触发了 ENOEXEC。

自有适配器另检查非法 JNI 参数，以及损坏 JSON、result 后还有事件、截断行和非法 UTF-8 的拒绝行为。

## 覆盖率验收构建

```sh
PDN_PROBE_NATIVE_COVERAGE=1 ./prepare.sh
./gradlew --offline -PprobeCoverage=true -Pandroid.aapt2FromMavenOverride="$(command -v aapt2)" :app:assembleDebug
```

运行 instrumentation 时增加 `-e nativeCoverage true`，结束后生成 `files/coverage.ec` 和 `files/native-coverage.profraw`。LLVM 只插桩本工程的 PTY JNI，Release PDN/loader 保持原样。正常构建不启用这些采集开关。
