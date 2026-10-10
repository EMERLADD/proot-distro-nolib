# 直接打包 .so 的 PDN 验证 App

独立 Java 工程，包名 `org.example.pdnsoleprobe`，minSdk 28、targetSdk 35。Gradle 只包含 `:app`，没有 AAR、Kotlin、引擎源码模块或原 App 依赖。`NativeRuntime` 使用宿主提供的私有目录和环境，通过 ProcessBuilder 启动 nativeLibraryDir 中的配套 ELF；`NativeOperations` 读取 JSONL 并交付回调。

独立 App 的安装、命令执行、事件、PTY 和启动错误处理已通过普通 Android App 身份下的实机验收。版本、逐项结果和覆盖率见 [测试记录](../../docs/pdn-error-testing.md#064-apk-接入实测)。

新增路径验收覆盖 `/usr` 映射、嵌套 bind、跨 rootfs 链接和缺失目标；所有夹具位于独立测试目录。该工程生成 Debug 和启用 R8 的 Release 测试 APK，用于验证和演示接入，不是生产应用。逐项结果见 [测试记录](../../docs/pdn-error-testing.md)。

## 构建

准备 [Release 0.6.6](https://github.com/EMERLADD/proot-distro-nolib/releases/tag/v0.6.6) 的 `libpdn.so`、`libproot-loader.so`，以及官方 Alpine 3.24.2 ARM64 归档，然后执行：

```sh
./prepare.sh /你的/Release目录 /你的/alpine-minirootfs-3.24.2-aarch64.tar.gz
./gradlew --offline -Pandroid.aapt2FromMavenOverride="$(command -v aapt2)" :app:assembleDebug
```

脚本未指定参数时使用仓库的 `build/releases/v0.6.6` 和 `build/pdn-sources`。SDK 使用 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT`，NDK 可通过 `PDN_NDK_DIR` 指定，默认 26.3.11579264。Termux 使用可运行的 Clang 搭配 NDK sysroot；Linux 主机默认使用 NDK Clang。首次获取 Gradle 依赖时去掉 `--offline`。

原生文件放在 `app/src/main/jniLibs/arm64-v8a/`，归档放在 assets；这些输入不纳入 Git。归档以 `alpine-rootfs.archive` 命名，保留原始 gzip 字节，避免构建工具自动解压 `.gz` asset。PDN 按内置大小和 SHA256 校验再安装。

脚本还编译这个工程自有的 `libprobepty.so`，负责 PTY、输入输出、resize 和子进程回收。它只依赖 Android libc/libdl，16 KiB 对齐。`libpdn.so` 与 loader 是原样打包的可执行 ELF，通过进程运行；只有自有 PTY 适配器通过 System.loadLibrary 加载。

构建 R8 验收版本：

```sh
./gradlew :app:assembleRelease
```

Termux 内使用：

```sh
./gradlew --offline -Pandroid.aapt2FromMavenOverride="$(command -v aapt2)" :app:assembleRelease
```

输出：`app/build/outputs/apk/release/app-release.apk`；Debug 输出仍为 `app/build/outputs/apk/debug/app-debug.apk`。Release 启用代码混淆、优化和资源压缩，使用 Android 默认优化规则，不添加宽泛 keep 规则。APK 保持不可调试，使用本机 Debug 测试密钥签名，不是生产签名；不启用 JaCoCo。构建前使用正常的 `./prepare.sh` 输入，不设置 `PDN_PROBE_NATIVE_COVERAGE`，运行时不传覆盖率参数。当前 App 版本为 0.1.5（versionCode 6）。

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

Release APK 不可使用 `run-as`。完整报告已由 instrumentation 写入标准输出，在本目录保存输出即可：

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am instrument -w org.example.pdnsoleprobe/.ProbeInstrumentation > release-acceptance.txt
```

检查输出报告的 `passed=true`、逐项检查以及最终 `INSTRUMENTATION_CODE: -1`。若界面未进入前台，沿用上面的并行启动 instrumentation 与显式启动 Activity 步骤，并将 instrumentation 输出重定向保存；省略最后的 `run-as` 命令。rish 可同样保存 `am instrument -w` 的输出。

PTY 子进程状态使用 `waitpid` 获取，不依赖 `/proc/<pid>` 可见性。运行中与成功退出 0 分开表示；自动验收覆盖运行中、退出 0/37、信号终止及已回收状态。

逐项验收内容、错误触发方式与 JNI 边界检查统一见 [测试记录](../../docs/pdn-error-testing.md#064-apk-接入实测)。

## 覆盖率验收构建

```sh
PDN_PROBE_NATIVE_COVERAGE=1 ./prepare.sh
./gradlew --offline -PprobeCoverage=true -Pandroid.aapt2FromMavenOverride="$(command -v aapt2)" :app:assembleDebug
```

运行 instrumentation 时增加 `-e nativeCoverage true`，结束后生成 `files/coverage.ec` 和 `files/native-coverage.profraw`。LLVM 只插桩本工程的 PTY JNI，Release PDN/loader 保持原样。正常构建不启用这些采集开关。

## 离线自动验收

`prepare.sh` 会编译静态 ARM64 文件操作探针并修正 Bionic TLS 对齐，需要 Android NDK、C 编译器和 Python 3。探针只存在于测试 APK 的 assets，不属于发行 ELF 或 AAR。

可使用 APK 内的官方 Alpine 归档直接运行完整接口验收，避免 GUI 在线安装的下载等待。它仍在普通 App 进程中运行，覆盖真实继承的 seccomp、三次 `openat2` SIGSYS、`ENOSYS` 与调用方 `openat` 回退、tar 往返、事件和 PTY；此模式不执行 GUI 点击验收。

```sh
adb shell am instrument -e suiteOnly true -w -r org.example.pdnsoleprobe/.ProbeInstrumentation
```
