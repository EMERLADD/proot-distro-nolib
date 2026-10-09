# PDN AAR 接口

0.6.6 仅打包 PDN 所需原生组件，保留配置对象、异步任务、独立终端和结构化发行版查询。最低 Android 9（API 28），目前提供 ARM64；宿主提供 Kotlin 标准库，不需要 Compose、协程或 Termux。

## 产物与目录

| 文件 | 内容 |
| --- | --- |
| `pdn-engine-lite-0.6.6.aar` | Java/Kotlin API、`libpdn.so`、`libproot-loader.so`、`libptyjni.so` |
| `pdn-engine-0.6.6.aar` | Java/Kotlin API、`libpdn.so`、`libproot-loader.so`、`libptyjni.so` |
| `libpdn.so`、`libproot-loader.so` | 单独打包的 PDN 与 loader ELF 可执行程序 |
| `pdn`、`proot-loader` | 同版本原始 ELF |

推荐导入 `pdn-engine-0.6.6.aar`；`pdn-engine-lite-0.6.6.aar` 是兼容旧下载名称的别名，两个文件字节完全相同，选一个导入即可。所有 PDN 终端接口和 PTY JNI 都保留。旧 `ProotLauncher` 类和方法仍保留以维持接口兼容，但依赖旧 pr-cli 的调用（例如 `startSession()`）不能仅靠本 AAR 运行；应改用 `PdnTerminal`，或由宿主自行提供旧原生程序。仓库原 App 单独打包旧组件，不再通过 AAR 携带。

AAR 内 `classes.jar` 包含 API，`jni/arm64-v8a/` 包含原生文件，Manifest 与 Android 元数据用于合并。PDN 和 loader 是可执行程序；`libptyjni.so` 是实际的 JNI 共享库。AAR 不包含已安装的 Linux，也不提供终端屏幕渲染控件。

Gradle 导入本地 AAR 后保留 `extractNativeLibs=true` 与 `jniLibs.useLegacyPackaging=true`，由 Android 将程序解压到 `nativeLibraryDir`。完整配置见 [App 接入教程](android-embedding.md)。`ProotHost` 仍提供程序目录、数据目录、缓存目录、项目相关目录和包名；路径由宿主传入。

## 配置

```kotlin
val configuration = PdnConfiguration(
    "root",
    "/workspace",
    listOf(PdnBind(extraDirectory, "/data")),
    mapOf("LANG" to "C.UTF-8")
)
val pdn = PdnRuntime(host, rootfsDirectory, projectDirectory, configuration)
val operations = PdnOperations(pdn)
```

配置会复制挂载列表和环境变量映射，创建后不可修改。账号、guest 工作目录、额外挂载和 guest 环境变量适用于 `login()`、`exec()` 和使用相同 ProcessBuilder 的终端。环境变量值通过独立 argv 传入，包含空格、引号和 `$` 的值保持原样。

默认将项目目录挂载到 `/workspace`；显式配置相同 guest 路径可以替换它。单次调用传入完整 `PdnConfiguration` 会替换运行时配置；旧 `user` 参数只覆盖账号，其余配置保留。它不会替代发行版内保存的 `pdn config` 文件。

## 异步任务

Java 可以直接调用：

```java
PdnTask task = operations.start(
    pdn.install("alpine"),
    new PdnListener() {
        @Override public void onEvent(PdnEvent event) {
        }
        @Override public void onComplete(PdnResult result) {
        }
        @Override public void onFailure(Exception failure) {
        }
    },
    120_000L,
    context.getMainExecutor()
);
```

`start()` 把任务交给后台线程，复制 ProcessBuilder 的命令、环境与工作目录。`onStdout`、`onStderr` 接收原始字节；事件与输出回调按处理顺序串行送到指定 Executor。省略 Executor 时在工作线程回调。stdout/stderr 的跨流先后顺序由操作系统决定。

`task.cancel()` 请求取消；`isDone()` 查询是否完成；`await()` 返回 `PdnResult` 或抛出 `ExecutionException`。只在后台线程等待。带等待时限的 `await(timeout, unit)` 超时不会取消任务；`start()` 的运行超时会取消任务。

取消和运行超时分别通过 `PdnHostException` 的 `host_operation_cancelled`、`host_operation_timeout` 提供原因与建议。清理先尝试 SIGTERM，让 PRoot 结束并回收 guest，再在必要时强制结束。`await()` 包含进程清理及最终回调完成。

后台池最多同时运行 4 个任务，队列最多 128 个。队列满时通过失败回调报告拒绝；排队任务取消后，轮到工作线程处理时直接完成，不启动原生进程。超时从提交时计算，包含排队时间。

Executor 必须执行已接受的回调，监听器必须返回；正在执行的回调会延迟取消完成。不要在回调线程里等待同一任务。Executor 拒绝回调时会在当前处理线程回退到失败通知。Linux 命令已启动后的非零退出仍通过 `onComplete(PdnResult)` 返回，不能仅靠 Java 异常判断 guest 成功。

原有 `operations.run(builder, listener)` 保持同步，回调在调用线程执行。

## 独立终端

```java
PdnTerminalSession session = new PdnTerminal(pdn).start(
    pdn.login("alpine"), 24, 80,
    new PdnTerminalListener() {
        @Override public void onOutput(byte[] data) { }
        @Override public void onEvent(PdnEvent event) { }
        @Override public void onComplete(PdnResult result) { }
        @Override public void onFailure(Exception failure) { }
    }
);
session.resize(32, 96);
session.write("apk add nano\n".getBytes(StandardCharsets.UTF_8));
```

每个会话独立持有 PID 和 PTY fd。高层终端负责读取输出与 JSONL 事件，并在资源清理后通知最终结果；监听器运行在该终端的后台监控线程，更新界面时由宿主切到主线程。多个终端互不覆盖 PID。

`write()` 可能只写入部分字节，返回 0 时稍后重试；完整输入需要循环写完。`resize()` 使用行数和列数，范围 1–65535。PTY 将 stdout/stderr 合并为一条字节流，宿主负责跨分块 UTF-8 解码和终端渲染。

`poll()` 在运行时返回 null，结束后返回缓存的 `PdnTerminalStatus`；`waitFor()` 等待并回收子进程。退出码与终止信号分别获取，正常退出 0 不再与“仍在运行”混淆。带时限的 `waitFor(timeoutMillis)` 未结束时返回 null。

`terminate()` 先 SIGTERM，500ms 后仍未结束则 SIGKILL；`close()` 终止、回收并关闭 fd，可重复调用。会话关闭后输入、读取和 resize 抛出异常。极少数处于不可中断内核等待的进程可能延迟回收；关闭操作应放在后台线程。宿主在 Activity、Service 或工作区结束时自行关闭对应任务和会话。

PRoot、loader、guest shell 启动失败沿用 `PdnResult` 的分类、消息和建议。PTY 创建、exec 和 I/O 的宿主失败通过 `PdnTerminalException` 提供操作、真实 errno 和建议；失败不会吞成 null。

需要自行读取 PTY 时可使用 `PdnTerminalSession.start(builder, rows, cols)`。读取返回正数表示字节数，-1 表示 PTY 挂断，0 表示暂无数据或 EOF；结合 `poll()` 判断退出，并在退出后排空剩余输出。不要与高层 `PdnTerminal` 同时读取同一 fd。新会话严格使用完整的 `ProcessBuilder.environment()`，清空环境会真的移除继承变量。

## 发行版与镜像

```java
PdnCatalog catalog = pdn.catalog();
List<PdnDistributionInfo> available = catalog.available();
List<PdnDistributionInfo> installed = catalog.installed();
List<PdnMirrorInfo> mirrors = catalog.mirrors("debian");
```

查询是同步操作，GUI 应在后台调用。返回列表和条目不可修改。可安装条目提供名称、固定版本、架构和下载字节数；已安装条目提供名称与 rootfs 路径，未知版本、架构和大小为 null，不从当前下载目录推断已有系统的版本。

镜像条目提供发行版、镜像名称、base URL、完整归档 URL、顺序与官方源标记。顺序是内置回退优先级，不是实时测速排名。

查询使用原生 `list --json`、`list --available --json`、`mirrors [NAME] --json` 的版本 1 数据封装，JSONL 事件仍在独立通道。格式、UTF-8、类型、版本或大小不合法时抛出 `host_catalog_protocol`；原生查询失败抛出 `PdnQueryException`，`getResult()` 保留原生错误原因和建议。

## 验证与发布范围

构建提供非插桩 Debug AAR、对应 ELF 和 `.so`，并保留旧 lite 下载名作为字节相同的别名。标准和 Termux 构建都不向 AAR 打包旧 pr 原生组件；发布脚本再次过滤，验证三项原生文件、API 元数据、字节一致性和校验值。0.6.5 的实机记录属于上一版本；0.6.6 本轮只验证 AAR 构建、SDK 单元测试和打包，不构建或重新测试 APK。

本轮没有加入 Maven 发布、正式 Release/R8 混淆验收、终端渲染控件或自动后台服务。独立 App 的构建与测试方式见 [AAR 验证工程](../examples/aar-probe/README.md)。
