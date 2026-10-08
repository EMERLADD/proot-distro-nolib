# PDN 事件接口与 AAR

PDN 0.6.2 提供协议 v1。Java/Kotlin 的调用方可直接获取阶段、进度、错误和最终结果，不需要解析终端文本。原有直接运行 `ProcessBuilder` 的接口仍可用。

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

`PdnRuntime.install()` 等方法返回尚未启动的 `ProcessBuilder`；`PdnOperations.run()` 启动它并等待结束。调用方必须放在工作线程。监听器回调按顺序在这个工作线程执行，更新 Android 控件时自行切到主线程。`run()` 关闭标准输入，适合安装和一次性命令；交互终端继续使用现有 PTY 接口。

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
| `cancelled` | 原生安装/归档操作捕获中断并完成清理 |
| `host_protocol_error` | Java 封装发现缺失、截断、不合法或退出码不一致的事件 |

以 `isSuccess()` 判断成功；不要只看 `exitCode == 0`。`getExitCode()` 是宿主实际 PDN 进程退出码；`getGuestExitCode()` 是主 guest 进程退出码，`getGuestSignal()` 是主 guest 终止信号。底层 PRoot 现有退出状态可能受到后退出的子进程影响，所以这两个退出码允许不同；信号终止也不强制换算为 `128 + signal`。`getSignal()` 表示安装/归档中断信号。

`error` 是诊断事件，不是最终结果。例如第一个镜像下载失败产生错误，第二个镜像成功后，最终结果仍然是 `success`。成功结果不会携带此前失败镜像的错误。界面只在最终失败时显示 `code`、`message`、`suggestion`，详细日志仍从 stderr 读取。

目录类错误给出对应环境变量和检查建议；安装阶段区分 `download_failed`、`verification_failed`、`extraction_failed`、`configuration_failed`。底层 PRoot 的部分错误仍归为 `manager_failed` 并要求查看 stderr。这版未提供完整后台任务管理或进程树取消接口。

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

阶段包括 `preparing`、`downloading`、`verifying`、`extracting`、`configuring`、`initializing`、`publishing`、`backing_up`、`restoring`、`starting`、`running`。下载百分比使用固定归档大小；备份/恢复给出已处理字节和未知总量 `-1`，不伪造百分比。guest 内 `apk` 等程序的进度仍属于原始日志，PDN 不解析它们。

原生对进度节流，每次操作最多 1024 条进度记录；错误字符串有长度上限并处理 JSON 转义和非法 UTF-8。Java 限制单行 16 KiB、最多 10000 条记录、总文件 8 MiB，标准流队列为 32 × 8192 字节。事件文件描述符 close-on-exec，协议变量进入 guest 前清除。事件不包含 guest 参数和环境值；原始日志由调用方决定如何保存。

正常返回包含一个 `started` 和一个最终 `result`。强制杀死、通道写入失败等情况可能没有完整结果，Java 会报告 `host_protocol_error`，不会猜测成功。后台子进程在 PDN 结束后仍持有标准流时，Java 最多再排空 2 秒，超过限制抛宿主 IO 错误；完整进程树管理属于后续工作。

## 当前 AAR 的内容

`android/proot-engine` 是引擎库模块，正常构建输出 `proot-engine-debug.aar` 或 `proot-engine-release.aar`：

```text
proot-engine-*.aar
├─ AndroidManifest.xml
├─ classes.jar
│   └─ id/or/oo/pr/engine/
│      ├─ ProotHost / PdnRuntime / AlpinePackages
│      ├─ PdnOperations / PdnListener / PdnEvent / PdnResult
│      └─ 现有 ProotLauncher / PtyNative 兼容接口
├─ jni/arm64-v8a/
│  ├─ libpdn.so
│  ├─ libproot-loader.so
│  ├─ libptyjni.so
│  ├─ libproot.so
│  ├─ libpr-cli.so
│  └─ libbusybox.so
└─ 构建工具生成的 R.txt、元数据等
```

`libpdn.so` 是独立 ELF 可执行程序，内含 PDN 命令管理、下载/解压依赖和修改后的 PRoot；`libproot-loader.so` 也是 ELF 程序。`.so` 名称用于 Android 原生库打包与解压，不代表可通过 JNI 调用 `install()`。W2 仍然用 `ProcessBuilder` 启动 PDN，Java API 负责参数、事件和结果封装。

`libptyjni.so` 才是给交互终端使用的 JNI 共享库。其他三个程序保留给旧引擎接口，当前 AAR 还包含兼容路径，并未拆成只含 PDN 的最小库。AAR 不包含终端 Compose UI、示例 App、Linux rootfs 或已经安装的软件；这些分别属于宿主界面或运行时数据。

宿主导入 AAR 后，还需配置 Kotlin 标准库依赖、ARM64、minSdk 28、原生库解压和联网权限。直接导入本地 AAR 不会自动携带 Maven 依赖声明。Java 工程可以使用本版 Java 接口，但 `PdnRuntime` 本身仍是 Kotlin 实现，运行时需要 Kotlin 标准库。路径全部由宿主提供，见 [Android 接入教程](android-embedding.md)。
