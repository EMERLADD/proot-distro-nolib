# PDN AAR 接口

简体中文 | [English](pdn-aar-api.en.md) · [返回 README](../README.md)

AAR 仅打包 PDN 所需原生组件，保留配置对象、异步任务、独立终端和结构化发行版查询。最低 Android 9（API 28），目前提供 ARM64；宿主提供 Kotlin 标准库，不需要 Compose、协程或 Termux。

## 产物与目录

| 文件 | 内容 |
| --- | --- |
| `pdn-engine-lite-版本号.aar` | Java/Kotlin API、`libpdn.so`、`libproot-loader.so`、`libptyjni.so` |
| `pdn-engine-版本号.aar` | Java/Kotlin API、`libpdn.so`、`libproot-loader.so`、`libptyjni.so` |
| `libpdn.so`、`libproot-loader.so` | 单独打包的 PDN 与 loader ELF 可执行程序 |
| `pdn`、`proot-loader` | 同版本原始 ELF |

推荐导入 `pdn-engine-版本号.aar`；`pdn-engine-lite-版本号.aar` 是兼容旧下载名称的别名，两个文件字节完全相同，选一个导入即可。终端会话、输入输出、窗口大小调整和多会话接口均保留，终端界面由宿主 App 提供。旧 `ProotLauncher` 类和方法仍保留以维持接口兼容，但依赖旧 pr-cli 的调用（例如 `startSession()`）不能仅靠本 AAR 运行；应改用 `PdnTerminal`，或由宿主自行提供旧原生程序。仓库原 App 单独打包旧组件，不再通过 AAR 携带。

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

## 安装下载续传

`install()` 与 `installAs()` 的参数和返回类型不变，不需要新增续传开关。在线下载中断或可处理取消后，以相同 rootfs 管理目录和完全相同的镜像 URL 再次提交同一安装操作，会自动尝试复用下载缓存。下载进度事件包含已有缓存字节，界面应使用事件报告的累计值。宿主决定何时再次提交；PDN 不会在后台自动重试。

缓存位于 rootfs 管理目录下的 `.pdn-downloads`，按固定 SHA256 与镜像 URL 摘要分开保存；切换镜像不会拼接字节。Range 响应不符合要求、服务器忽略 Range 或返回 416 时，最多从零重启一次。完整大小和 SHA256 校验通过后才解压，损坏缓存删除，成功缓存消费；普通解压暂存目录仍会清理。暂不支持 ETag / If-Range。通过 `pdn.catalog().cache()` 查询并用 `pdn.clearCache()` 清理；安装锁被占用时清理返回错误。详见[发行版管理](pdn-distributions.md)。

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

## 安装别名与独立实例

```kotlin
val operation = pdn.installAs("alpine", "ai-python", mirror = "official")
```

```java
ProcessBuilder operation = pdn.installAs("alpine", "ai-python");
ProcessBuilder offline = pdn.installAs("alpine", "ai-node", null, archiveFile);
```

这与 `install()` 一样返回 `ProcessBuilder`，应交给已有操作接口执行。第三、第四参数分别为镜像与固定版本归档，二者互斥。后续 exec、login、backup 和 remove 使用实例名。不同实例的 rootfs 独立；显式绑定的共享项目目录仍然共享。

元数据保存在 rootfs 的 `.pdn-instance`。安装在暂存目录写完后才发布；恢复保留可知的来源版本和摘要，生成新 ID、名称与时间。结构损坏时 `list --json` 返回 `instance_metadata_invalid`，不输出半截 JSON；元数据写入失败返回 `instance_metadata_failed` 并回滚暂存目录。元数据可被 guest 修改，不能作为安全身份或授权依据。

## 复制和重命名

```kotlin
val copy = pdn.clone("ai-python", "ai-python-test")
val rename = pdn.rename("ai-python-test", "workspace-python")
```

```java
ProcessBuilder copy = pdn.clone("ai-python", "ai-python-test");
ProcessBuilder rename = pdn.rename("ai-python-test", "workspace-python");
```

仍交给已有操作接口执行，接收同一事件/结果协议。clone 生成新 ID/时间，来源为 `clone`，保留可知的版本与摘要；旧实例可产生来源字段为 null 的克隆。rename 保留 ID、时间和来源，旧 rootfs 可以继续没有元数据。名称冲突不区分大小写，允许仅改变大小写的 rename。

运行中的实例或已有管理锁返回 `operation_busy`。clone 复用备份的可移植复制规则，需要暂存归档和完整目标 rootfs 空间；rename 直接移动目录并迁移自身宿主绝对链接、`.l2s` backing 路径与 rootfs 内部 bind。外部项目 bind 保持共享，环境字符串和普通文件内容不自动重写。普通失败与 SIGINT/SIGTERM 在发布前回滚；回滚失败返回 `rollback_failed` 并保留恢复快照，需按建议检查。SIGKILL/掉电恢复仍待实现。

## 发行版与镜像

```java
PdnCatalog catalog = pdn.catalog();
List<PdnDistributionInfo> available = catalog.available();
List<PdnDistributionInfo> installed = catalog.installed();
List<PdnMirrorInfo> mirrors = catalog.mirrors("debian");
```

查询是同步操作，GUI 应在后台调用。返回列表和条目不可修改。可安装条目提供名称、固定版本、架构和下载字节数；已安装条目提供名称与 rootfs 路径；`getInstance()` 返回可选的不可变 `PdnInstanceInfo`。其中包含稳定 ID、实例名、发行版及版本、架构、来源、来源 URL、归档 SHA256 和 Unix 秒创建时间。旧 rootfs 的实例信息为 null，查询不会改写它；来源未知的恢复或复制实例可以保留 null 字段，不根据当前下载目录推断历史版本。

镜像条目提供发行版、镜像名称、base URL、完整归档 URL、顺序与官方源标记。顺序是内置回退优先级，不是实时测速排名。

查询使用原生 `list --json`、`list --available --json`、`mirrors [NAME] --json` 的版本 1 数据封装，JSONL 事件仍在独立通道。格式、UTF-8、类型、版本或大小不合法时抛出 `host_catalog_protocol`；原生查询失败抛出 `PdnQueryException`，`getResult()` 保留原生错误原因和建议。

## 接入注意事项

标准 AAR 与 lite 文件内容相同，均保留完整终端接口。宿主需提供 Kotlin 标准库。支持 R8 混淆，使用 Android 默认优化及 JNI 规则，无需保留整个 SDK。当前没有 Maven 发布、终端渲染控件或自动后台服务。可参考 [AAR 示例](../examples/aar-probe/README.md)。

## 下载缓存

通过结构化查询显示占用，清理操作沿用异步任务和事件接口。查询是同步方法，在 GUI 的工作线程调用；清理前在界面取得确认。

```java
PdnDownloadCacheInfo cache = pdn.catalog().cache();
long bytes = cache.getTotalBytes();
List<PdnDownloadCacheEntry> entries = cache.getEntries();
PdnDownloadCacheInfo alpine = pdn.catalog().cache("alpine");
PdnTask task = operations.start(pdn.clearCache("alpine"), listener);
```

`pdn.cache()` / `cache("alpine")` 返回 JSON 查询的 `ProcessBuilder`；`pdn.clearCache()` 清理全部可识别格式的下载残片。快照和条目不可修改，条目提供 `getId()`、`getDistro()`、`getMirror()`、`getBytes()`、`getTotal()` 和 `getUpdatedAt()`；最后一项为 Unix 秒，未知来源字段可为 null。大小不是校验通过的证明。


## 系统文件选择器、FD 与流

`pdn.documents()` 提供同步的 `install`、`installAs`、`restore`、`backup`。输入可为 `InputStream`、`ParcelFileDescriptor`，或 `ContentResolver` 与 `Uri`；备份输出可为 `OutputStream`、文件描述符，或 resolver 与 URI。Java/Kotlin 均可调用。宿主使用 `ACTION_OPEN_DOCUMENT` 选择压缩包、`ACTION_CREATE_DOCUMENT` 新建备份文档，并保留所需 URI 读写授权。PDN 不负责打开选择器界面。

```java
PdnDocuments documents = pdn.documents();
PdnResult installed = documents.installAs("alpine", "workspace", resolver, inputUri, listener);
PdnResult saved = documents.backup("workspace", resolver, outputUri, listener);
PdnResult restored = documents.restore("workspace-copy", resolver, backupUri, listener);
```

这些方法会阻塞，必须在工作线程调用；监听器也在该线程执行。原生事件与 stdout/stderr 继续回调，`onComplete` 在文件传输与暂存清理完成后调用；读写或宿主失败通过异常抛出，不自动调用 `onFailure`。原生失败返回非成功的 `PdnResult`。导出的原生 `result` 事件仅表示原生备份阶段结束，整个导出应以方法返回或 `onComplete` 为准。

```java
try (ParcelFileDescriptor fd = resolver.openFileDescriptor(inputUri, "r")) {
    PdnResult result = documents.restore("workspace-copy", fd, listener);
}
```

FD 会被复制，SDK 关闭副本，不关闭传入 FD；副本与原 FD 共享当前位置。调用方提供的流不会被关闭，URI 打开的流由 SDK 关闭。传输从当前位置开始；流与 FD 必须保持有效，避免并发读写。线程中断可取消传输和原生操作，但文档提供者若阻塞且忽略中断，取消需等待它返回；这些接口不返回 `PdnTask`。

输入先保存到宿主私有缓存的临时目录，再交给原生接口。安装仍验证固定发行版大小与 SHA256；任意自带 rootfs 包应使用 `restore`。备份先生成完整私有归档，成功后才打开 URI 输出并复制。需预留完整归档大小的额外私有缓存空间，正常完成、失败和可处理取消后清理暂存；进程被强杀或掉电后的遗留恢复暂未实现。

`host_document_io_failed` 表示文档权限、提供者、存储空间或流读写失败。输出文档可能在导出失败后留有部分内容，无法保证跨提供者事务回滚；选择新文档保存，失败后删除不完整文件再重试。线程中断后同样检查输出文档。共享存储／SD 卡可以存放压缩包和备份，这不表示它们适合作为 Linux rootfs 安装目录。


## 中断安装清理接口

```java
PdnRecoveryInfo info = pdn.catalog().recovery();
List<PdnRecoveryEntry> entries = info.getEntries();
PdnTask task = operations.start(pdn.clearRecovery(selectedId), listener);
```

查询为同步方法，在工作线程调用。`pdn.recovery()` 返回 JSON 查询的 ProcessBuilder，`pdn.clearRecovery()` 清理全部明确中断的原生安装暂存，传入 ID 则只处理该条目；启动清理前由界面取得确认。快照不可修改，条目提供目录、ID、目标、阶段、PID、启动时钟计数、boot ID、创建时间、状态及原因，未知字段为 null。启动时钟计数不是 Unix 时间；创建时间为 Unix 秒。

`isInterrupted()` 仅说明安装身份判断，清理时仍重新拿锁并检查目录和会话；可能返回 `operation_busy` 或 `recovery_unsafe`。未知记录不自动删除。此接口不恢复原任务事件或会话，也不处理 restore、clone/rename 或文档选择器的私有暂存。详细规则见 [发行版管理](pdn-distributions.md#中断安装检查与清理)。
