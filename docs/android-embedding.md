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
PDN 和 loader 应来自同一次构建。GitHub Release 已提供独立 AAR；完整调用方式见 [AAR API](pdn-aar-api.md)。`install()`、`exec()`
等方法属于 Kotlin 封装，负责组装路径、环境和参数数组，PDN 没有 C/JNI 方法接口。

## 通过 AAR 接入

从同一 Release 下载 `pdn-engine-版本号.aar`，放到 App 的 `libs/`。0.6.6 起 AAR 包含 Java/Kotlin 接口，以及 PDN、配套加载器和 PTY JNI 三个原生文件，保留全部终端会话接口；终端界面由宿主 App 提供。标准版与 lite 内容相同，选一个导入。

```kotlin
dependencies {
    implementation(files("libs/pdn-engine-0.6.6.aar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
}
```

示例标准库版本与仓库 AAR 使用的 Kotlin 版本一致；已有 Kotlin 工程应按宿主依赖配置统一版本。纯 Java App 也可以调用 AAR，不需要为了调用接口添加 Kotlin Android 插件。

以下 SDK、原生库解压和网络权限配置同样适用于 AAR。由宿主实现 `ProotHost`，通过 `PdnRuntime` 生成操作，使用 `PdnOperations.start()` 接收异步事件和结果；同步 `run()` 需放在后台线程。交互终端使用 `PdnTerminal`，界面和生命周期由宿主负责。

目录契约、Java/Kotlin 示例、配置、取消/超时和终端关闭见 [AAR API](pdn-aar-api.md)，完整独立工程见 [AAR 示例](../examples/aar-probe/README.md)。后文直接放置两个 ELF 的步骤用于自行管理进程的接入方式；导入 AAR 后无需重复放置其中已包含的原生文件。

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
- [AlpinePackages](../android/proot-engine/src/main/java/id/or/oo/pr/engine/AlpinePackages.kt)：通过 exec 接口安装软件、更新索引和查询软件。

仓库内可使用 `:proot-engine` 模块。其他项目可引入这些 Kotlin 源码和 W2 的四份 Java 接口源码，
并保留许可材料；仅使用 `PdnRuntime` 的进程接口不需要 PTY JNI 或终端 UI。

也可以构建 `:proot-engine:assembleDebug`，把
`android/proot-engine/build/outputs/aar/proot-engine-debug.aar` 放到宿主的 `app/libs/`：

```kotlin
dependencies {
    implementation(files("libs/proot-engine-debug.aar"))
}
```

宿主需提供 Kotlin 标准库，并保留上面的 ARM64、SDK、原生库解压及网络权限配置。
Java 工程也可使用 Java 监听器 API；Kotlin 实现需要的运行时依赖仍须保留。
AAR 已包含原生程序，不要再重复放同名 `.so`；引擎 AAR 不包含终端 UI 模块。


```kotlin
import android.content.Context
import id.or.oo.pr.engine.AlpinePackages
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

## 事件监听与 Java 接口

W2 新增 `PdnOperations.run(builder, listener)`、`PdnEvent`、`PdnResult` 与 `PdnListener`，
支持阶段、进度、分类错误和最终结果，stdout/stderr 独立回调。
API 保持进程调用方式，不要求 JNI 或协程；回调与等待在工作线程执行。
使用 GUI 接入时优先选择这个接口，详见 [事件协议、Java/Kotlin 示例和 AAR 结构](pdn-events.md)。

## 安装与执行 API

`PdnRuntime` 提供 `install(name)`、`remove(name)`、`login(name, user)` 和
`exec(name, command, user)`，均返回尚未启动的 `ProcessBuilder`。调用 `.start()`
才会启动进程，可先配置合并输出或重定向。`remove()` 使用 `--yes`，调用前由
App 确认删除。`login` 和 `exec` 也接受完整 rootfs `File`，默认把项目目录挂载到
`/workspace` 并设为工作目录；默认 guest 身份为 `root`。

用独立参数数组调用，不把输入内容拼成整条 shell 命令。所有等待进程和读取
输出的操作放到工作线程。下面是基础的合并输出调用，回调在 IO 线程执行：

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun runPdn(
    builder: ProcessBuilder,
    onLine: (String) -> Unit,
): Int = withContext(Dispatchers.IO) {
    val process = builder.redirectErrorStream(true)
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
val pdn = createRuntime(context)
val installStatus = runPdn(pdn.install("alpine"), onLine)
check(installStatus == 0) { "Installation failed: $installStatus" }

val status = runPdn(pdn.exec("alpine", listOf(
    "/bin/printf", "%s", "two words",
)), onLine)

val shellStatus = runPdn(pdn.exec("alpine", listOf(
    "/bin/sh", "-c", "pwd; ls -la /workspace",
)), onLine)
```

其他常用方法：

```kotlin
pdn.version()
pdn.list()
pdn.list(available = true)
pdn.mirrors("alpine")
pdn.install("alpine", mirror = "official")
pdn.install("alpine", archive = File(context.filesDir, "alpine.tar.gz"))
pdn.backup("alpine", File(context.filesDir, "backup.tar.gz"))
pdn.restore("alpine-copy", File(context.filesDir, "backup.tar.gz"))
pdn.config("alpine")
pdn.saveConfig("alpine", listOf("--user", "1000:1000", "--work-dir", "/home"))
pdn.clearConfig("alpine")
```

这些方法同样返回 `ProcessBuilder`，不会自动启动或等待。`config()` 查询 JSON；
`saveConfig()` 的选项列表直接传给核心，替换全部保存选项；`clearConfig()` 清除。
登录与执行封装显式指定默认 root 身份和 `/workspace`，优先于保存配置；要使用
配置中的身份、工作目录或其他启动参数，可调用 `processBuilder()`。

`status` 保留 guest 命令的退出码。安装、执行等命令的输出仍是文本，没有
统一的事件 JSON 协议；`config --show` 才是配置 JSON。示例合并 stdout/stderr，
需要分别读取时由宿主并行消费两个流，避免阻塞。

交互登录可用 `pdn.login("alpine")` 构建进程；需要终端交互时使用下面的 PTY
接口，将 `pdn.login(File(pdn.rootfsDir, "alpine")).command()` 交给同一宿主的
`launcher.startCustomSession(arguments, rows, columns)`；完整路径避免宿主默认
rootfs 父目录与自定义目录不同。
`processBuilder(arguments)` 保留为通用入口，用于更多挂载、环境变量等高级参数。

删除前先由 App 在界面确认，然后调用 `pdn.remove("alpine")`。
后台 Service、任务取消、超时及完整进程树回收由宿主负责；这份基础示例没有
实现完整任务管理，也不保证 coroutine 取消能立即中断阻塞的流读取。

## Alpine 图形安装示例

示例 App 的 Alpine 卡片下提供“Alpine 软件”面板。先通过界面安装 Alpine，
再输入 `git curl` 等包名或点选常用软件，点击“安装软件”。安装前自动更新索引；
“更新索引”和“已安装软件”也可以单独点击。执行时禁用重复操作并显示日志，
退出码为零才显示成功，不打开终端或要求输入命令。

已实测通过该界面安装 `curl`，随后在 Alpine 中执行
`curl -v https://example.com/` 可正常访问。此记录验证 GUI 安装流程及安装后
Linux 程序的 HTTPS 访问；curl 的访问测试是在 Alpine 内执行。

面板通过 `AlpinePackages` 调用上面的 `PdnRuntime.exec()`：

```kotlin
val packages = AlpinePackages(pdn, File(pdn.rootfsDir, "alpine"))
val status = runPdn(packages.install("git curl"), onLine)
val updateStatus = runPdn(packages.update(), onLine)
val listStatus = runPdn(packages.installed(), onLine)
```

安装在 guest 中执行 `/bin/sh -c`，先 `apk update`，成功后执行 `apk add`。
包名作为独立位置参数传入，不拼入 shell 表达式；输入只接受包名，拒绝命令、
选项及路径。包名允许字母、数字及 `+`、`_`、`.`、`-`，多个包用空白分隔。
软件版本约束和本地 `.apk` 文件安装需通过通用接口接入。

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

以上 `ProotLauncher` 示例保留兼容用法；新接入推荐 `PdnTerminal` 和
`PdnTerminalSession`，支持独立 PID/fd、多会话、退出状态和显式终止/关闭。
配置、项目路径、输入分块和生命周期要求见 [AAR API](pdn-aar-api.md)。
依赖旧 pr-cli 的 `startSession()` 需要宿主自行提供旧原生组件，PDN AAR 不再携带它们。

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

GitHub Release 0.6.4 原件已分别验证 rish 原始 ELF 28/28、独立 AAR App 19/19、直接 `.so` App 24/24。两个 App 的实际运行身份为普通 `untrusted_app`，targetSdk 35；rish 仅作为安装和测试入口，不能代替 App 身份。

本地 0.6.5 AAR 的独立 App 扩展验收 33/33，覆盖异步任务、配置、查询和双终端。0.6.6 的 Ubuntu 调用 Android 命令路径也在 MT 管理器中复现成功。各版本的原件对应关系、覆盖率和测试范围见 [测试记录](pdn-error-testing.md)；Android shell 操作见 [教程](pdn-shizuku-android-shell.md)。
