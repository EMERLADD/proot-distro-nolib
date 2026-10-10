# PDN 事件接口与 AAR

简体中文 | [English](pdn-events.en.md) · [返回 README](../README.md)

PDN 使用协议 v1。Java/Kotlin 的调用方可直接获取阶段、进度、错误和最终结果，不需要解析终端文本。原有直接运行 `ProcessBuilder` 的接口仍可用。

当前 SDK 的异步任务、不可变配置、结构化查询和独立终端见 [AAR API](pdn-aar-api.md)。下文保留同步 `run()` 和协议 v1 的接入说明，仍可使用；交互终端推荐 `PdnTerminal`。

## 调用关系

```text
宿主 GUI / Java / Kotlin
  ├─ ProotHost：程序、数据、缓存和项目路径
  ├─ PdnRuntime：install / exec / backup 等参数与环境
  └─ PdnOperations.run(builder, listener)
       ├─ 启动 nativeLibraryDir/libpdn.so 独立进程
       ├─ stdout → onStdout(byte[])
       ├─ stderr → onStderr(byte[])
       └─ 私有 JSONL 文件 → onEvent(PdnEvent)
                              └─ 最终校验 → PdnResult / onComplete
```

`PdnRuntime.install()` 等方法返回尚未启动的 `ProcessBuilder`；`PdnOperations.run()` 启动它并等待结束。调用方必须放在工作线程。监听器回调按顺序在这个工作线程执行，更新 Android 控件时自行切到主线程。`run()` 关闭标准输入，适合安装和一次性命令；交互终端使用 `PdnTerminal` 或底层 `PdnTerminalSession`。

```java
PdnRuntime pdn = new PdnRuntime(host);
PdnOperations operations = new PdnOperations(pdn);
PdnResult result = operations.run(pdn.install("alpine"), new PdnListener() {
    @Override public void onEvent(PdnEvent event) {
        if ("progress".equals(event.getType())) {
            Integer percent = event.getPercent();
        }
    }
    @Override public void onStderr(byte[] data) {
    }
});
if (!result.isSuccess()) {
    String reason = result.getMessage();
    String advice = result.getSuggestion();
}
```

这里的 `host` 实现 `ProotHost`，Java 中提供 `getNativeLibDir()`、`getPrefixDir()`、`getHomeDir()`、`getCacheDir()` 和 `getPackageName()`。`PdnRuntime` 的默认参数已有 Java 重载；自定义 rootfs/项目目录可调用三参数构造器。

```kotlin
val result = runInterruptible(Dispatchers.IO) {
    PdnOperations(pdn).run(
        pdn.exec("alpine", listOf("/bin/sh", "-c", "apk update && apk add curl")),
        object : PdnListener {
            override fun onEvent(event: PdnEvent) {
            }
        },
    )
}
```

监听器不依赖协程。协程只是 Kotlin 示例选择的工作线程方式。参数校验错误抛 `IllegalArgumentException`；创建缓存、启动进程、读写通道等宿主失败抛 `IOException`；线程中断抛 `InterruptedException`。监听器自己抛出的异常在清理后继续抛出。PDN 操作失败和 Linux 命令退出通过 `PdnResult` 表达。

不要对交给 `run()` 的 builder 合并或重定向标准流；三个流必须保持独立 PIPE。原 builder 的命令、目录和环境不会被修改。`onStdout` / `onStderr` 收到独立的原始字节分块，不保证每次是一行或一个完整 UTF-8 字符，也不保证两个不同流之间的全局先后顺序。

## 最终结果

| outcome | 含义 |
| --- | --- |
| `success` | PDN 完成，实际进程退出码为 0 |
| `manager_error` | 参数、目录、下载、校验、解压或启动阶段失败 |
| `guest_exit` | Linux 程序已启动，观察到非零退出或信号终止 |
| `cancelled` | 原生安装、归档或实例操作捕获中断并完成清理 |
| `host_protocol_error` | Java 封装发现缺失、截断、不合法或退出码不一致的事件 |

以 `isSuccess()` 判断成功；不要只看 `exitCode == 0`。`getExitCode()` 是宿主实际 PDN 进程退出码；`getGuestExitCode()` 是主 guest 进程退出码，`getGuestSignal()` 是主 guest 终止信号。底层 PRoot 现有退出状态可能受到后退出的子进程影响，所以这两个退出码允许不同；信号终止也不强制换算为 `128 + signal`。`getSignal()` 表示原生管理操作捕获的中断信号。

`error` 是诊断事件，不是最终结果。例如第一个镜像下载失败产生错误，第二个镜像成功后，最终结果仍然是 `success`。成功结果不会携带此前失败镜像的错误。界面只在最终失败时显示 `code`、`message`、`suggestion`，详细日志仍从 stderr 读取。

目录类错误区分不存在、不是目录、权限不足及只读；安装、校验、归档和配置错误从实际失败点提供更细的分类。具体分类与逐项测试触发方法见 [错误分类与验证](pdn-error-testing.md)。启动错误从 PRoot、loader、guest shell 和 ELF interpreter 的实际失败点提供分类，详见下表。未能取得具体原因的内部错误仍保留兜底分类和 stderr。`PdnOperations.start()` 提供后台任务、运行超时和取消；通过 `PdnTask` 查询或等待完成，清理先尝试正常结束并在必要时强制终止。接口与回调线程约束见 [AAR API](pdn-aar-api.md)。

### PRoot 与 guest 启动错误

| code | 含义 |
| --- | --- |
| `guest_shell_missing` / `guest_shell_nonexecutable` / `guest_shell_bad_format` / `guest_shell_failed` | 初始 `/bin/sh` 缺失、无法执行、格式不支持或其他执行失败 |
| `guest_login_shell_missing` / `guest_login_shell_nonexecutable` / `guest_login_shell_bad_format` / `guest_login_shell_failed` | 包装层已启动，但实际交互登录 shell 无法启动 |
| `guest_interpreter_missing` / `guest_interpreter_nonexecutable` / `guest_interpreter_bad_format` / `guest_interpreter_failed` | 启动 shell 所需的 ELF interpreter 缺失、无法执行、格式错误或其他读取/加载失败 |
| `proot_loader_missing` / `proot_loader_nonexecutable` / `proot_loader_bad_format` / `proot_loader_failed` | 外部或内嵌 PRoot loader 的准备或执行失败 |
| `launch_pipe_failed` / `launch_fork_failed` | 启动诊断管道或进程创建失败 |
| `ptrace_failed` | 启动阶段的追踪声明、选项设置或恢复执行失败 |
| `guest_exec_failed` | 启动子进程报告执行失败，且没有更具体的诊断 |
| `loader_open_failed` / `loader_mapping_failed` / `loader_close_failed` | loader 装载期间打开、映射或关闭文件失败，保留实际错误 |
| `guest_start_failed` / `guest_login_failed` | guest 或实际登录 shell 在装载通知之前退出，无法取得更具体原因 |

这些失败返回 `manager_error`，通过现有 `getCode()`、`getMessage()`、`getSuggestion()` 获取，不需要修改 Java/Kotlin catch。系统调用错误的 message 保留实际 errno。权限失败只说明执行被拒绝，并建议检查权限、挂载和平台策略日志，不根据一个 errno 断言具体策略原因。

启动成功的边界是 PRoot loader 完成映射并发出装载通知；交互登录还要完成实际登录 shell 的装载。之后的命令不存在、退出 126/127、信号终止，以及 guest 动态链接器自身在运行时报告共享库缺失，仍是 `guest_exit`，详细原因从原始 stderr 获取。此接口不解析动态链接器文案，也不保证 guest 的初始化脚本执行成功。

父进程统一写事件。启动子进程失败通过 close-on-exec 管道报告，避免父子分别输出最终结果；即使系统尝试把坏 shell 回退为文本脚本并退出 0，已经确认的启动失败也不会被发布为 success。

Java 宿主的缓存、事件文件、进程启动、流读写及清理错误抛出 `PdnHostException`，它继承 `IOException`，保留原始 `cause`，新增 `getCode()` 与 `getSuggestion()`。原有 `catch (IOException)` 继续有效；参数错误、线程中断及监听器自身抛出的异常保持原有行为。清理失败不会覆盖前面的异常，而是通过 `getSuppressed()` 附加。

```java
try {
    PdnResult result = operations.run(pdn.install("alpine"), listener);
} catch (PdnHostException failure) {
    String code = failure.getCode();
    String advice = failure.getSuggestion();
    Throwable cause = failure.getCause();
}
```

## 原生 JSONL 协议

原生接口是可选环境变量 `PDN_EVENT_FILE` 与 `PDN_OPERATION_ID`，没有设置通道时保留原 CLI 输出。通道必须是进程拥有的私有空普通文件，或者尚不存在的新文件；拒绝符号链接、特殊文件、非空文件、多个硬链接和 group/other 可访问的文件。Java 自动在宿主缓存目录创建 0600 文件，结束后删除。

每行 UTF-8 JSON，以换行结束。公共字段：

| 字段 | 内容 |
| --- | --- |
| `version` | 固定为 1 |
| `operation_id` | 1–64 个 ASCII 字母、数字、`.`、`_`、`-`；Java 自动生成 |
| `sequence` | 从 1 开始连续递增 |
| `operation` | `install`、`exec`、`backup` 等操作名 |
| `type` | `started`、`stage`、`progress`、`error`、`result` |

示例：

```json
{"version":1,"sequence":1,"operation_id":"demo","operation":"install","type":"started"}
{"version":1,"sequence":2,"operation_id":"demo","operation":"install","type":"stage","stage":"downloading"}
{"version":1,"sequence":3,"operation_id":"demo","operation":"install","type":"progress","stage":"downloading","current":1024,"total":2048,"percent":50}
{"version":1,"sequence":4,"operation_id":"demo","operation":"install","type":"result","stage":"publishing","outcome":"success","exit_code":0}
```

阶段包括 `preparing`、`downloading`、`verifying`、`extracting`、`configuring`、`initializing`、`publishing`、`backing_up`、`restoring`、`cloning`、`renaming`、`starting`、`running`。下载百分比使用固定归档大小；备份/恢复/复制给出已处理字节和未知总量 `-1`，不伪造百分比。guest 内 `apk` 等程序的进度仍属于原始日志，PDN 不解析它们。

原生对进度节流，每次操作最多 1024 条进度记录；错误字符串有长度上限并处理 JSON 转义和非法 UTF-8。Java 限制单行 16 KiB、最多 10000 条记录、总文件 8 MiB，标准流队列为 32 × 8192 字节。事件文件描述符 close-on-exec，协议变量进入 guest 前清除。事件不包含 guest 参数和环境值；原始日志由调用方决定如何保存。

正常返回包含一个 `started` 和一个最终 `result`。强制杀死、通道写入失败等情况可能没有完整结果，Java 会报告 `host_protocol_error`，不会猜测成功。后台子进程在 PDN 结束后仍持有标准流时，Java 最多再排空 2 秒，超过限制抛宿主 IO 错误；异步取消和运行超时会执行同一清理流程，详见 [任务接口](pdn-aar-api.md#异步任务)。这不保证回收已脱离追踪的守护进程，也不覆盖不可中断内核等待或 SIGKILL 后的持久恢复。

## 当前 AAR 的内容

`android/proot-engine` 是引擎库模块，正常构建输出 `proot-engine-debug.aar` 或 `proot-engine-release.aar`：

```text
proot-engine-*.aar
├─ AndroidManifest.xml
├─ classes.jar
│   └─ id/or/oo/pr/engine/
│      ├─ ProotHost / PdnRuntime / AlpinePackages
│      ├─ PdnOperations / PdnTask / PdnListener / PdnEvent / PdnResult / PdnHostException
│      ├─ PdnConfiguration / PdnBind
│      ├─ PdnCatalog / PdnDistributionInfo / PdnInstanceInfo / PdnMirrorInfo / PdnQueryException
│      ├─ PdnTerminal / PdnTerminalListener / PdnTerminalSession / PdnTerminalStatus / PdnTerminalException
│      └─ ProotLauncher / PtyNative 兼容接口
├─ jni/arm64-v8a/
│  ├─ libpdn.so
│  ├─ libproot-loader.so
│  └─ libptyjni.so
└─ 构建工具生成的 R.txt、元数据等
```

`libpdn.so` 是独立 ELF 可执行程序，内含 PDN 命令管理、下载/解压依赖和修改后的 PRoot；`libproot-loader.so` 也是 ELF 程序。`.so` 名称用于 Android 原生库打包与解压，不代表可通过 JNI 调用 `install()`。API 使用 `ProcessBuilder` 启动 PDN，Java API 负责参数、事件和结果封装。

`libptyjni.so` 是给交互终端使用的 JNI 共享库。标准 AAR 仅包含上述三项原生文件，`pdn-engine-lite-版本号.aar` 是字节相同的兼容文件名。旧 Java/Kotlin 类保留接口兼容，但依赖旧 pr-cli 的方法需要宿主另行提供原生程序；PDN 操作与终端应使用当前 API。AAR 不包含终端 Compose UI、示例 App、Linux rootfs 或已经安装的软件；这些分别属于宿主界面或运行时数据。

宿主导入 AAR 后，还需配置 Kotlin 标准库依赖、ARM64、minSdk 28、原生库解压和联网权限。直接导入本地 AAR 不会自动携带 Maven 依赖声明。Java 工程可以使用本版 Java 接口，但 `PdnRuntime` 本身仍是 Kotlin 实现，运行时需要 Kotlin 标准库。路径全部由宿主提供，见 [Android 接入教程](android-embedding.md)。

## 独立 App 实测

生成的 AAR 已在 [全新的 Java Android 验证 App](../examples/aar-probe/README.md) 中实测可用。该工程不引用原项目模块，仅导入 AAR 与 Kotlin 标准库；进入 Alpine 后，在交互终端成功执行 `apk add nano`。AAR 和直接 `.so` 接入均已通过 Debug 与 R8 Release 的独立 App 验收，包含初始化、发行版安装、命令执行、事件和 PTY；各版本的实际原件、检查项数和覆盖范围见 [测试记录](pdn-error-testing.md)，不将旧版结果当作新版验收。
