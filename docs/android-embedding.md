# Android App 接入 PDN

适用于当前 ARM64 Android 产物。PDN 在运行时使用 Android 的 libc/libdl，
下载、TLS 和解压依赖已静态链接；宿主无需安装 Termux。

## Release 文件怎么选

| 文件 | 用途 |
| --- | --- |
| `pdn` | Android shell、MT 管理器等允许直接执行程序的宿主 |
| `libpdn.so` | 同一程序改为 APK 原生库文件名，供 App 通过进程启动 |
| `proot-loader` | 普通命名的配套 loader |
| `libproot-loader.so` | APK 原生库命名的配套 loader |
| 完整 `.tar.gz` | 程序、jniLibs 布局、教程、对应源码和许可材料 |
| `SHA256SUMS` | 独立下载文件与完整包的校验值 |

`libpdn.so` 与 `pdn` 内容相同，仍是可执行程序，不是提供 JNI 导出函数的共享库。
通过 `ProcessBuilder` 或 PTY 的 `execve` 调用，不使用 `System.loadLibrary("pdn")`。
PDN 和 loader 应来自同一次构建。当前没有独立发布的 AAR，也没有 `install()`
这样的 PDN C/JNI 接口；下面的 Kotlin 封装负责组装路径、环境和参数数组。

## 放进 APK

将两个文件放入你的 App 模块：

```text
app/src/main/jniLibs/arm64-v8a/
├── libpdn.so
└── libproot-loader.so
```

`build.gradle.kts` 中设置 ARM64 和原生库解压打包：

```kotlin
android {
    defaultConfig {
        minSdk = 28
        targetSdk = 35
        ndk { abiFilters += "arm64-v8a" }
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}
```

这里采用仓库示例 App 的 SDK 配置。独立 PDN 的构建目标为 API 24；采用这份
App/JNI 示例时使用 API 28 及以上。宿主的其他代码可以要求更高 SDK。

在 Manifest 的 `manifest` 节点下声明网络权限：

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

安装后从 `context.applicationInfo.nativeLibraryDir` 获取程序位置。
数据与项目文件放在 `context.filesDir`，临时文件放在 `context.cacheDir`。
不要把 rootfs 解压到 `/sdcard`；共享目录可通过 `--bind` 挂载给 Linux 使用。
这个方案依赖 Android 解压原生库，必须检查成品 APK 的 `extractNativeLibs=true`。

## Kotlin 路径与环境封装

本仓库已提供：

- [ProotHost](../android/proot-engine/src/main/java/id/or/oo/pr/engine/ProotHost.kt)：宿主目录契约。
- [PdnRuntime](../android/proot-engine/src/main/java/id/or/oo/pr/engine/PdnRuntime.kt)：准备目录、生成 argv/environment 和 ProcessBuilder。

仓库内可使用 `:proot-engine` 模块。其他项目可把这两份 Kotlin 源码引入自己的
模块并保留许可材料；仅使用 `PdnRuntime` 的进程接口不需要 PTY JNI 或终端 UI。

```kotlin
import android.content.Context
import id.or.oo.pr.engine.PdnRuntime
import id.or.oo.pr.engine.ProotHost
import java.io.File

class AndroidPdnHost(context: Context) : ProotHost {
    private val app = context.applicationContext
    override val nativeLibDir = File(app.applicationInfo.nativeLibraryDir)
    override val prefixDir = File(app.filesDir, "usr")
    override val homeDir = File(app.filesDir, "home")
    override val cacheDir = File(app.cacheDir, "pdn")
    override val packageName = app.packageName
}

fun createRuntime(context: Context): PdnRuntime = PdnRuntime(
    host = AndroidPdnHost(context),
    rootfsDir = File(context.filesDir, "linux"),
    projectDir = File(context.filesDir, "projects/current"),
)
```

`prepare()` 检查程序和 loader 是否存在并可执行，再创建宿主数据、缓存、rootfs
父目录和项目目录。默认 rootfs 父目录为 `prefixDir/var/lib/pdn/rootfs`，项目目录
为 `homeDir/workspace`，调用方可以像上面一样显式覆盖。

主要环境变量：

| 变量 | 内容 |
| --- | --- |
| `PDN_ROOTFS_DIR` | 宿主指定的发行版父目录 |
| `PROOT_LOADER` | `nativeLibraryDir/libproot-loader.so` |
| `PROOT_TMP_DIR`、`TMPDIR` | 宿主缓存目录；启动前必须存在 |
| `HOME`、`APP_HOME` | 宿主 home 目录 |
| `APP_PREFIX` | 宿主程序数据前缀，与 Termux 的 `$PREFIX` 无关 |
| `APP_PACKAGE` | 当前宿主包名 |
| `PROOT_NO_SECCOMP` | 当前封装设为 `1`，保持现有 Android 适配 |

PDN 核心使用 `PDN_ROOTFS_DIR` 和显式参数决定数据位置；`APP_*` 是现有宿主
封装的契约。进入 guest 后，PDN 会设置 guest 的 HOME、PATH、USER 等环境。

## 安装与执行 API

用独立参数数组调用，不把输入内容拼成整条 shell 命令。所有等待进程和读取
输出的操作放到工作线程。下面是基础的合并输出调用，回调在 IO 线程执行：

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun runPdn(
    runtime: PdnRuntime,
    arguments: List<String>,
    onLine: (String) -> Unit,
): Int = withContext(Dispatchers.IO) {
    val process = runtime.processBuilder(arguments)
        .redirectErrorStream(true)
        .start()
    try {
        process.outputStream.close()
        process.inputStream.bufferedReader().use { reader ->
            reader.forEachLine(onLine)
        }
        process.waitFor()
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}
```

调用示例，放在 coroutine 或其他宿主任务中：

```kotlin
val runtime = createRuntime(context)
val installStatus = runPdn(runtime, listOf("install", "alpine"), onLine)
check(installStatus == 0) { "Installation failed: $installStatus" }

val rootfs = File(runtime.rootfsDir, "alpine")
val status = runPdn(runtime, listOf(
    "exec", "--rootfs", rootfs.absolutePath,
    "--bind", "${runtime.projectDir.absolutePath}:/workspace",
    "--work-dir", "/workspace",
    "--", "/bin/sh", "-c", "printf '%s\\n' \"\$1\"",
    "pdn-task", "two words",
), onLine)
```

`status` 保留 guest 命令的退出码。安装、执行等命令的输出仍是文本，没有
统一的事件 JSON 协议；`config --show` 才是配置 JSON。示例合并 stdout/stderr，
需要分别读取时由宿主并行消费两个流，避免阻塞。

删除前先由 App 在界面确认，然后调用 `listOf("remove", "alpine", "--yes")`。
后台 Service、任务取消、超时及完整进程树回收由宿主负责；这份基础示例没有
实现完整任务管理，也不保证 coroutine 取消能立即中断阻塞的流读取。

## 交互终端与 PTY

交互登录需要 PTY 和终端模拟器。仓库已有
[ProotLauncher](../android/proot-engine/src/main/java/id/or/oo/pr/engine/ProotLauncher.kt)、
[PTY JNI](../android/proot-engine/src/main/cpp/ptyjni.c) 和
[TerminalActivity](../android/app/src/main/java/id/or/oo/pr/TerminalActivity.kt)。
`libptyjni.so` 才是由 `System.loadLibrary("ptyjni")` 加载的 JNI 共享库，
它不是独立 PDN Release 的必需文件；使用其他终端库时可以接入自己的 PTY。

```kotlin
val host = AndroidPdnHost(context)
val launcher = ProotLauncher(host)
val session = launcher.startPdnSession(rootfs, rows = rows, cols = columns)
    ?: error("Cannot start terminal")
session.write("pwd\n".toByteArray())
session.resize(newRows, newColumns)
```

`host` 是上面的宿主对象，`rootfs` 是已安装的完整 rootfs 路径。使用仓库的
PTY 实现时还要编译并打包 `libptyjni.so`。按实际布局测量行列数后再启动会话，
屏幕或字号变化时调用 `resize()`；在工作线程持续读取 `Session.read()` 的字节
送给终端模拟器，键盘输入则调用 `write()`。

当前 `Session.close()` 是基础关闭接口，完整会话生命周期、多会话 PID 管理
仍需完善。通过 `startPdnSession` 启动时，项目目录采用 `host.homeDir/workspace`；
自定义项目路径可用 `runtime.command(runtime.loginArguments(rootfs))` 与自己的
PTY 接入，并传入 `runtime.environment()`。

## 编译和打包

独立程序与 Release 文件：

```sh
make NDK_PATH=/你的/NDK/目录
make package NDK_PATH=/你的/NDK/目录
```

编译输出在 `build/proot-distro-nolib/arm64/`，包含 `jniLibs/arm64-v8a/`。
发布输出在 `build/packages/`，包含独立程序、两个 `.so`、完整包和校验值。
发布脚本要求提交项目变更，以便完整包内的源码对应构建版本。GitHub 工作流
将这些文件上传为 Actions artifact；上传 artifact 本身不会创建 GitHub Release。
正式 Release 上传独立文件时，同时附上完整包和 `SHA256SUMS`。

构建仓库的示例 APK，在 Android/Termux 上执行：

```sh
sh scripts/build-android-termux.sh assembleDebug --no-daemon
```

该脚本使用 NDK sysroot、可在 Android 上运行的 Clang、兼容 Gradle 和原生 aapt2，
打包现有 PDN/loader，并核对复制后的摘要。先运行独立 PDN 构建以更新引擎。
APK 输出是 `android/app/build/outputs/apk/debug/app-debug.apk`。

## 当前验证范围

Android 宿主路径封装有单元测试与覆盖率门槛。独立 PDN 的执行、配置、安装、
归档回归已在允许 PRoot 运行的 ARM64 Android 环境验证。rish 的 UID 2000 Android shell 中已实测版本输出及 Alpine 安装；该场景的 guest 登录
还待确认。设备 instrumentation 测试已可打包，当前会话没有通过 ADB 执行它。
