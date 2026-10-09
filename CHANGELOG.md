# proot-distro-nolib 更新记录

## v0.6.5 — 2026-10-09

- AAR 增加不可变配置与 Java/Kotlin 兼容构造器，统一账号、guest 工作目录、挂载和原样环境变量参数。
- 增加后台任务、取消、超时、等待与串行 Executor 回调；限制工作队列，回收 PRoot 和 guest 子进程后完成。
- 终端会话独立持有 PID/fd，提供输入、resize、等待、终止和重复关闭，区分正常退出与信号，保留真实 JNI errno 与原生启动错误。
- 增加结构化发行版和镜像查询，严格校验 JSON、UTF-8、版本和类型，保留未知安装元数据。
- 每次发布增加 `pdn-engine-lite-0.6.5.aar`，仅包含 PDN、loader 与 PTY JNI，完整版与 `.so`/ELF 继续提供。
- 独立 Java App 只依赖轻量 AAR 和 Kotlin 标准库：普通 `untrusted_app` 进程实测 33/33，通过 GUI 操作、真实安装、异步取消/超时与双终端测试；JNI 实机行覆盖率 269/292（92.12%）。
- JVM 90 项通过；原生 PDN 203 项通过、2 项跳过，PRoot 16 项通过，打包 9 项通过。SDK 单元与实机合并行覆盖率 944/1044（90.42%），新增/修改原生可执行行覆盖率 94/96（97.92%）。
- 正式 Release/R8 混淆与 Maven 发布不在本轮范围。接入说明见 `docs/pdn-aar-api.md`。

## v0.6.4 — 2026-10-09

- PRoot 启动、loader、初始 guest shell、ELF interpreter 和实际登录 shell 的启动失败提供具体错误码、原始 errno 和建议。
- 启动子进程通过独立管道把失败原因交给父进程；JSONL 由父进程输出，避免子进程失败时重复 started/result 或打乱 sequence。
- 已进入 guest 的命令非零退出、126/127 和信号终止仍保留 `guest_exit`；不通过 stderr 文案判断错误，也不把权限拒绝直接断言为 SELinux/seccomp 原因。
- 保持事件协议 v1 和 Java/Kotlin 接口兼容；生成配套 AAR、`.so` 和原始 ELF，补测两种独立接入 APK，未重建原 pr App。
- 使用 Release 原件完成三条设备验收：rish 原始 ELF 28/28、独立 AAR APK 19/19、直接 `.so` APK 24/24。两种 APK 在 Android 14 的普通 App 进程中运行，targetSdk 35；初始化、安装、执行、事件与 PTY 交互均通过。


验证：211 项原生测试，209 项通过、2 项因环境限制跳过；其中启动测试 41 项全部通过，包含真实 musl 登录和受控 loader 系统调用。54 项 JVM 测试与 6 项发布打包检查通过。新增/修改原生可执行行覆盖率 95.05%，事件模块 98.09%，Java/Kotlin 96.11%；AAR 原生文件与对应 ELF 逐字节一致。故障注入、实测范围和加载后的诊断边界见 [错误验证](docs/pdn-error-testing.md)。

## v0.6.3 — 2026-10-08

- 目录、挂载来源、配置、用户解析、卸载、安装冲突与锁错误从实际失败点分类，锁占用只用于真正的 flock 冲突；统一 `directory_not_directory`。
- 下载区分 DNS、连接、超时、TLS、HTTP 和其他传输失败；文件写入、关闭失败优先报告权限、只读、空间不足或 I/O 原因，保留已捕获的 errno。
- 校验区分大小、SHA256 与摘要计算失败；解压/恢复区分损坏、不安全、不支持及超限归档，保留拒绝覆盖和失败回滚。
- 泛化包装保留具体诊断；镜像验证成功后清除旧失败，后续配置错误不会被已恢复的网络错误遮住。
- Java 新增 `PdnHostException extends IOException`，提供宿主缓存、事件通道、进程启动、流和清理错误码及建议；保留原始 cause、回调异常与中断行为，清理失败通过 suppressed 附加。
- GUI 显示 Java 宿主错误类别和建议；App 元数据更新为 1.0.3 / versionCode 4，未构建原 App APK。
- 补充每项错误的测试触发、真实失败/故障注入区分及未验证边界；PRoot/guest 启动内部错误细分留待后续。

验证：170 项原生测试执行，168 项通过、2 项跳过（真实 `/dev/full` 与硬链接通道测试受环境限制）；53 项 JVM 测试全部通过，含真实 PDN 与 guest 对接。原生行覆盖率：前端 94.13%、配置 100%、卸载 93.84%、事件 97.66%、备份恢复 95.75%；安装器使用同源测试 harness 为 99.67%。Java/Kotlin 行覆盖率 96.11%，`PdnHostException` 为 100%。原 App Kotlin 编译和最终 AAR/原始 ELF 一致性校验通过。
只读、空间不足等故障的注入验证不代表实际挂载只读文件系统或写满设备；OOM 不代表耗尽设备内存。完整说明见 [错误分类与逐项验证](docs/pdn-error-testing.md)。

## v0.6.2 — 2026-10-08

- 补发 v0.6.1 / v0.6.2 Release；后续版本自动发布配套 AAR、`.so`、原始 ELF、完整源码包与 SHA256，拒绝 AAR 与原生附件版本混用。
- 新增可选 JSONL 操作事件：阶段、下载及归档进度、错误建议和最终结果；不混入 Linux stdout/stderr。
- 增加 Java `PdnOperations`、`PdnListener`、`PdnEvent`、`PdnResult`，Kotlin 默认参数提供 Java 重载。
- 区分 PDN 管理/启动错误、guest 非零退出、信号终止与协议异常；保留实际进程退出码。
- GUI 接入事件监听，显示原生阶段和进度，最终失败时显示错误类型、原因及建议。
- 通道使用私有缓存文件，校验事件版本与顺序，限制记录大小和流缓冲；异常与线程中断后清理资源。
- 补充 Java/Kotlin 接入示例、协议说明与 AAR 实际目录结构。
- App 元数据更新为 1.0.2 / versionCode 3，生成引擎 Debug AAR；引擎更新阶段没有构建原 App 的 APK。
- 新增独立 Java Android 验证 App（0.1.0），仅导入生成的 AAR 与 Kotlin 标准库，提供初始化、安装、exec、事件显示及 PTY 交互。
- AAR 在独立 App 中已实测可用：进入 Alpine 后，通过交互终端成功执行 `apk add nano`。

验证：144 项原生测试执行，143 项通过、1 项因环境不允许创建硬链接而跳过；42 项 JVM 测试通过，包含实际 PDN 与 Linux 命令的 Java 对接。
原生行覆盖率：事件模块 97.27%、命令前端 98.43%、安装器 98.81%、备份恢复 97.07%；Java/Kotlin 行覆盖率 96.44%。GUI Kotlin 编译与 AAR 原生程序哈希校验通过。
独立验证 APK 的构建、签名、Manifest、原生文件与 AAR 哈希校验通过；正常 APK 不含 JaCoCo。独立 App 自动验收及实机覆盖率报告尚未采集，手动验证不替代这些报告。

## v0.6.1 — 2026-10-08

- Debian rootfs 下载源只保留 `official` 直链，移除重定向到同一文件的 `github` 重复入口；固定版本、文件大小和 SHA256 校验保持不变。
- Alpine 增加图形软件安装、索引更新和已安装软件查询，使用 Kotlin `exec()`，显示进度及真实退出状态。
- Kotlin `PdnRuntime` 增加安装、登录、执行、删除、列表、镜像查询、备份恢复及配置读写方法，返回可配置的 `ProcessBuilder`。
- 安装方法支持显式官方源和离线归档；核实四个发行版已有官方 ARM64 源，补充下载源文档及回归检查。
- Android 示例 App 接入独立 PDN，使用宿主提供的程序、数据、缓存和项目路径。
- 加入终端字号设置、键盘避让及测量行列数后启动，移除旧工作区页面。
- 修复显式 root 身份重复参数警告；目录报错补充实际路径、来源变量和系统原因。
- 发布输出增加 `libpdn.so`、`libproot-loader.so` 和 jniLibs 布局，提供 Android 接入教程。

验证：101 项原生回归通过（含 85 项 PDN 测试）；前端行覆盖率 99.14%，安装器 98.76%。
宿主路径、API 及 Alpine 软件安装封装 20 项单元测试通过，PdnRuntime 行覆盖率 98.68%，AlpinePackages 100%；APK 构建与签名校验通过。
实机通过 GUI 在 Alpine 中安装 curl，随后 `curl -v https://example.com/` 访问成功。
rish 的 Android shell 中已实测版本输出和 Alpine 安装，rish 登录仍待确认。

## v0.6.0 — 2026-10-06

- 新增 `config NAME` 保存每个 rootfs 的默认挂载、用户、工作目录和环境变量，支持 `--show`、`--clear` 及登录时 `--no-config`。
- `login`/`exec` 新增 `--user/-u`、`--work-dir/-w`、`--env/-e`；命令行覆盖默认值，环境变量仅在 guest 内设置。
- 配置采用有大小限制的数据格式，原子替换、拒绝配置链接，写入与清除受会话锁保护。
- 新增离线 `backup NAME FILE.tar.gz`、`restore NEW_NAME FILE.tar.gz`；拒绝覆盖，临时归档/恢复目录通过后才发布，支持中断清理。
- 备份迁移 PRoot 硬链接模拟使用的内部绝对路径；还原后可独立于旧系统继续读写及增删链接。
- 备份排除宿主启动配置、临时 loader、运行时目录内容和特殊节点；保留普通文件数据、模式与时间，目录补齐 owner rwx、不还原宿主所有者和 setid。

验证：93 项自动测试通过；前端行覆盖率 99.11%，配置模块 100%，备份恢复模块 96.88%。四个已有系统的用户、工作目录与环境变量验证通过；真实 Alpine 备份、换名恢复、保存配置后启动通过。

## v0.5.0 — 2026-10-06

- 新增 `pdn exec NAME -- COMMAND ARG...`，复用登录环境与会话锁，保留参数边界、标准输入输出和退出码。
- `login` 和 `exec` 支持重复 `--bind HOST[:GUEST]` / `-b HOST[:GUEST]`，支持文件、目录及带空格路径。
- 挂载只对当前会话生效，写入直接影响宿主文件；不保存配置、不修改 App 包名或增加 Termux 依赖。
- 保留 `login NAME -- COMMAND` 和 `--rootfs PATH` 用法，错误参数明确拒绝。

验证：70 项自动测试通过，前端行覆盖率 98.91%。已有 Alpine、Ubuntu、Debian、Arch 的 exec 与挂载文件读写均验证通过。

## v0.4.1 — 2026-10-06

- 修复 Android 宿主附加组泄漏到 guest，导致登录时 `groups: cannot find name for group ID` 的问题。
- 在 PRoot fake-id 层统一虚拟附加组，支持查询、设置、清空、参数与权限检查，以及 fork/exec 继承。
- 保持 Android 实际组权限不变，不改写 guest 的 `/etc/group`；已有系统替换二进制后重新登录即可生效。

验证：61 项自动测试通过；新增附加组模块行覆盖率 92.59%。
已有 Alpine、Ubuntu、Debian、Arch Linux ARM 的交互登录均只显示 `root` 组且无未知组警告，
组文件保持不变；Ubuntu apt 联网更新通过。原生 ARM64 路径已验证。

## v0.4.0 — 2026-10-06

- 新增 `pdn install ubuntu`、`pdn install debian`、`pdn install arch`，继续支持 Alpine；均为 ARM64，命令和名称兼容大小写。
- 固定 Ubuntu Base 24.04.5 LTS、Debian 13 trixie slim 20261005、Arch Linux ARM 2026.08，逐个校验下载大小和 SHA256。
- Ubuntu 支持清华、中科大、官方源；Arch 支持清华、中科大、南大、美国镜像；Debian 使用同一固定上游 GitHub 提交的两个下载入口。
- 新增 `pdn list --available` 和 `pdn mirrors NAME`；各发行版均支持自动失败切换、`--mirror` 和校验后的 `--archive` 本地安装。
- 适配 apt 软件源、Android CA 证书和 PRoot 下的运行配置；移除 Debian 容器专用缓存清理钩子。
- Arch 自动初始化 pacman 密钥，保留软件包签名校验，并为软件源配置备用地址。
- 归档硬链接转为相对符号链接，目录补充所有者访问权限；安全替换发行版的 DNS 链接，不跟随其目标。
- 按发行版设置下载超时和解压上限。Arch 官方包约 791 MiB，解压约 2 GiB。
- 保留已有 rootfs 保护、安装/卸载锁、登录会话保护以及无 Termux 运行依赖。

验证：59 项自动测试通过；前端行覆盖率 98.65%，安装模块 98.72%，发行版目录 100%。
三个新增发行版均已验证真实下载安装、登录、软件源更新以及安装运行 `tree`；
Arch 归档已验证官方 PGP 签名。运行时依赖仍仅为 Android libc/libdl。
详细来源与摘要见 [rootfs 目录](docs/pdn-rootfs-sources.md)。Ubuntu 安装与登录已实测通过；附加组名称警告在 v0.4.1 修复。

## v0.3.2 — 2026-10-06

- 新增 `pdn uninstall NAME`，别名 `pdn remove NAME`，命令和发行版名称忽略 ASCII 大小写。
- 卸载前显示实际目录，只有输入 `y` / `yes` 才删除；`--yes` / `-y` 可用于已有确认流程的 App 调用。
- 可删除配置目录下手动部署的发行版；卸载包含整个 rootfs 及其中的用户文件。
- 拒绝路径穿越、歧义名称、根目录符号链接和 `/` 父目录；内部符号链接只删除链接，拒绝跨文件系统遍历。
- 与安装共用操作锁，新版登录会话持有目录锁，使用中的系统拒绝卸载；不会强制结束会话。
- 删除失败返回非零并说明可能已经部分删除；不提供撤销。旧版登录和直接调用底层 PRoot 不参与会话锁，卸载前须退出。

验证：51 项自动测试通过；前端行覆盖率 98.64%，新增卸载模块 94.59%。
覆盖确认取消、EOF、链接目标保护、只读目录、权限失败、并发锁及确认期间目录被替换。
测试只操作临时 rootfs；本版于 2026-10-06 在 MT 管理器试用成功。

## v0.3.1 — 2026-10-06

- Alpine 下载源增加中科大、南大、官方 CDN、dotsrc，默认顺序为清华、中科大、南大、官方、dotsrc。
- 网络、HTTP、文件大小或 SHA256 校验失败时自动切换下一源；每次重新下载同一份固定版本 rootfs。
- 新增 `pdn mirrors`，以及 `pdn install alpine --mirror NAME` 手动选源；命令和镜像名称支持大小写混用。
- 在线安装的 apk 软件源跟随成功下载源；本地压缩包安装仍使用清华软件源。
- 取消操作立即停止尝试其他源，保留已有系统，失败仍清理本次安装目录。
- 本版不包含自动测速、断点续传或其他发行版安装。

验证：42 项自动测试通过；已实测中科大和官方 CDN 的下载安装、登录，
并验证中科大软件源的 `apk update`。安装模块行覆盖率 96.44%。
MT 管理器上的本版试用结果待确认。

## v0.3.0 — 2026-10-06

- 新增 `pdn install alpine`：从清华 TUNA 下载 Alpine 3.24.2 ARM64 minirootfs，校验大小和 SHA256 后部署。
- 新增 `pdn install alpine --archive PATH`：从本地同版本官方压缩包安装。
- 安装沿用 `PDN_ROOTFS_DIR`，配置清华 apk 软件源和基础 DNS。
- 加入安装锁、已有目录保护、临时目录部署及失败或中断清理。
- 默认登录启用现有硬链接兼容功能，支持 apk 索引写入和软件安装。
- HTTPS 与解压组件静态链接，运行时仍仅依赖 Android 系统 libc/libdl。
- 保留 `login`、指定命令执行、`list`/`ls`、大小写兼容和底层 PRoot 入口。

验证：39 项自动测试通过；前端行覆盖率 98.48%，安装模块行覆盖率 95.92%。
已实测清华下载、本地安装、Alpine 登录、`apk update` 以及安装运行 `tree`。
v0.3.0 于 2026-10-06 在 MT 管理器中试用成功。

功能实现提交：`8948b58`。
ARM64 `pdn` / `proot-distro-nolib` 产物 SHA256：

```text
33d316cefc00535334989fa8b95b86dd393a85b67bf87882d0f55edb5628b8e2
```
