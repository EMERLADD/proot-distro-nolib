# PDN 错误分类与逐项验证

PDN 0.6.4 沿用事件协议 v1。`outcome` 表示最终结果，`code` 表示具体失败原因，`message` 与 `suggestion` 提供说明和建议。Linux 命令退出仍是 `guest_exit`，不因为退出码碰巧相同而变成管理器错误。

## 测试方法

- **真实失败**：实际执行文件操作、flock、curl 或 libarchive，用受控输入触发失败，再检查实际退出码、JSONL 最终结果、建议和回滚后的文件。
- **故障注入**：只在测试编译的 harness 中替换某个函数，让它返回指定错误。发行程序不包含这些开关；它验证处理路径，不能证明设备真的发生过该故障。
- **映射验证**：直接给公共事件函数传入 errno，验证分类、建议和事件序列。它不验证文件系统或分配器能否真实产生该 errno。

测试不靠修改报错文字或让外网偶然失效，不写满设备存储，也不修改系统挂载。

## 原生管理错误

| 分类 | 怎样触发与验证 | 验证性质 |
| --- | --- | --- |
| `invalid_argument` | 缺少参数、无效 bind 格式、无效环境变量和配置参数；检查非零退出及分类 | 真实参数校验 |
| `rootfs_missing` | 指向不存在的 rootfs；备份、配置、卸载选择不存在名称 | 真实文件操作 |
| `directory_missing` | rootfs 父目录或显式临时目录不存在 | 真实文件操作 |
| `directory_not_directory` | 把普通文件用作 rootfs、父目录或临时目录 | 真实文件操作 |
| `directory_permission` | 无特权进程对目录移除访问权限，再尝试访问；结束后还原权限 | 真实权限失败 |
| `directory_read_only` | 测试版 rootfs 错误函数接收 EROFS；当前没有挂载可控的只读测试目录 | 映射验证；未进行真实目录只读验证 |
| `directory_unavailable` | 保留未知目录错误兜底；不能从未观察到的 errno 推测原因 | 非逐 errno 实机验证 |
| `bind_source_missing` / `bind_source_unavailable` | 不存在的挂载来源、FIFO 等不支持的来源类型 | 真实文件操作与类型校验 |
| `name_ambiguous` | 创建两个仅大小写不同的系统目录，再配置、备份或卸载 | 真实目录扫描 |
| `rootfs_exists` / `file_exists` | 重复安装/恢复到已存在名称，备份到已存在文件；同时检查原内容没被替换 | 真实冲突 |
| `operation_busy` | 测试进程持有真正的 flock，另一个 PDN 进程执行安装、配置、卸载、备份或恢复 | 真实跨进程锁冲突 |
| `lock_failed` | 锁文件设置为被拒绝的符号链接；另用测试版 flock 返回 ENOLCK/EIO/EINTR，确认不被误报成忙碌，且保留 errno | 真实安全校验＋故障注入 |
| `distro_unknown` / `mirror_invalid` | 选择不存在的发行版或镜像名称 | 真实参数校验 |
| `resolution_failed` | 测试版 curl 返回无法解析主机的错误码，断言未发起服务器请求 | 故障注入；未依赖真实 DNS 故障 |
| `connection_failed` | 请求本地未监听端口 | 真实 curl 连接失败 |
| `download_timeout` | 本地 HTTPS 服务器延迟响应，测试发行版超时设为 1 秒 | 真实 curl 超时；测试参数缩短等待 |
| `tls_failed` | 本地证书只匹配 localhost，请求时改用 IP；保持证书校验开启 | 真实 TLS 主机名校验失败 |
| `http_error` | 本地 HTTPS 服务器返回 404 | 真实 HTTP 失败 |
| `download_failed` | 测试版 curl 返回接收失败；保留未知传输错误兜底 | 故障注入 |
| `file_missing` | 选择不存在的本地归档、恢复文件或配置所需目录 | 真实文件操作 |
| `file_permission` | 对配置文件、备份来源或目录移除访问权限；下载写入另注入 EACCES | 真实权限失败＋故障注入 |
| `file_read_only` | 测试版下载写入返回 EROFS，检查最终结果不是网络错误 | 故障注入；未验证真实只读挂载 |
| `storage_full` | 测试版下载写入与关闭文件返回 ENOSPC；公共映射验证 EDQUOT | 故障注入＋映射验证；未写满设备 |
| `file_io_failed` | 测试版写入/关闭返回 EIO，确认原因保留 | 故障注入；其他无法细分的 I/O 保留此兜底 |
| `out_of_memory` | 公共事件函数与测试版 rootfs 错误函数接收 ENOMEM，检查错误码与建议 | 映射验证；未耗尽设备内存，也未逐个分配点注入 |
| `archive_size_mismatch` | 本地归档缩短一字节；服务器另返回超过固定大小的数据 | 真实大小校验与下载上限 |
| `archive_checksum_mismatch` | 保持文件大小不变，仅翻转内容中的一个字节 | 真实 SHA256 不匹配 |
| `archive_hash_failed` | 测试版摘要函数失败 | 故障注入 |
| `archive_corrupt` | 输入非归档数据、损坏或截断的归档；检查未发布 rootfs | 真实 libarchive 失败 |
| `archive_unsafe` | 归档包含 `../` 路径、危险链接或备份目标位于 rootfs 内；检查外部文件不被改写 | 真实安全校验 |
| `archive_unsupported` | 归档包含不支持的 FIFO 条目 | 真实类型校验 |
| `archive_limit_exceeded` | 测试安装的解压上限缩小；恢复输入包含超出单文件上限的归档头，不实际生成巨型文件 | 真实限制校验；安装限制采用测试参数 |
| `archive_invalid` | 恢复输入不是普通文件、归档缺少顶层 Linux rootfs | 真实输入及结构校验 |
| `extraction_failed` | 测试版写入归档条目失败 | 故障注入；明确原因优先使用文件或归档分类 |
| `rootfs_configuration_failed` | 测试版配置写入失败；同时覆盖先镜像失败、后回退成功、再配置失败的连续场景 | 故障注入；检查旧下载错误没有遮住新原因 |
| `keyring_initialization_failed` | 测试初始化子过程返回失败，检查未发布 Arch rootfs | 故障注入；不等于实机 pacman keyring 故障 |
| `publish_failed` | 测试版安装 rename 返回错误，检查临时安装内容被清理 | 故障注入；不改变实际发布系统调用 |
| `config_invalid` / `config_unsafe` | 写入损坏、过大或无效配置；配置入口设为符号链接或 FIFO | 真实解析与安全校验 |
| `config_read_failed` / `config_write_failed` | 无明确 errno 分类时的配置 I/O 兜底；实际权限失败优先返回 `file_permission` | 兜底分类；未逐个读写调用点注入 |
| `user_invalid` | 无效 UID/GID、不存在的用户名、损坏 passwd 内容 | 真实解析与用户查找 |
| `uninstall_failed` / `uninstall_incomplete` | 已接入卸载失败点，并记录是否确实删除过条目；具体系统原因可优先返回文件错误 | 部分删除故障尚未单独注入；不能声称已完整实测 |
| `manager_failed` | 无更具体原因的管理失败，测试版直接返回非零而不提前报告错误 | 兜底路径验证；0.6.3 未包含 PRoot/guest 启动内部细分；0.6.4 的补充验证见后文 |

原先的 `not_directory` 统一为 `directory_not_directory`；临时目录的权限不足、只读分别报告，不再都并入 `directory_not_writable`。旧阶段类 `verification_failed`、`configuration_failed`、`archive_failed` 等仍作为无法提供更具体原因时的兼容兜底。

## Java 宿主错误

`PdnHostException` 继承 `IOException`，保留原始 cause；增加 code 和 suggestion，不改成需要替换原有 catch 的另一套异常。

| 分类 | 怎样触发与验证 | 验证性质 |
| --- | --- | --- |
| `host_cache_failed` | 缓存路径设置为普通文件 | 真实文件操作 |
| `host_event_channel_failed` | 将缓存目录放到不可创建文件的 `/proc/self`；测试另关闭事件文件后尝试读取 | 真实文件操作＋故障注入 |
| `host_process_start_failed` | 启动不存在的执行文件；测试进程启动器另抛 SecurityException | 真实进程启动失败＋故障注入 |
| `host_output_failed` | 测试 Process 的输入关闭失败、输出读取失败、读取停滞超过排空期限 | 故障注入 |
| `host_cleanup_failed` | 测试 Process 拒绝退出、流关闭失败；另把事件文件替换为非空目录使删除真实失败 | 故障注入＋真实文件操作 |

还检查：原 `IOException` 捕获仍可用，cause 对象不被替换；监听器自身的异常原样传播；线程中断继续抛 `InterruptedException`；清理失败通过 suppressed 附加，不覆盖原始失败。

## 成功与退出状态回归

- 第一个镜像 404、第二个提供正确归档：允许前面出现 error 诊断，但最终是 success，不携带旧错误。
- 镜像回退成功后配置失败：最终是配置错误，不能返回已经恢复的 HTTP 错误。
- Linux 命令退出 0、37，以及信号终止：使用真实 PDN/guest 调用，核对 stdout、stderr、guest 状态和实际进程退出码。
- 拒绝覆盖、安全归档失败、安装/归档取消：检查现有内容和外部文件未被改写，临时文件被回收。

## 执行入口

原生测试需要 ARM64 Android 环境、可执行的 BusyBox fixture 与 NDK。常规入口为 `make test`；额外的错误处理测试位于 `tests/test_pdn_system_errors.py`。Java 入口为 `:proot-engine:testDebugUnitTest` 与 `:proot-engine:pdnCoverage`，真实原生对接测试还需设置 `PDN_NATIVE_FIXTURE`、`PDN_GUEST_FIXTURE`。

本环境缺少 `/dev/full`，因此真实 ENOSPC 测试跳过；硬链接事件通道测试也因文件系统拒绝创建硬链接而跳过。所有故障注入结果都不能替代独立 Android App 的实机覆盖率报告。

## 0.6.3 验证记录

原生 170 项执行，168 项通过、2 项按上述原因跳过；Java/Kotlin 53 项全部通过，真实 PDN/guest fixture 已启用。原 App Kotlin 编译、AAR ZIP 完整性、新异常类和原生程序逐字节一致性检查通过，没有构建原 App APK。

行覆盖率：前端 94.13%、配置 100%、卸载 93.84%、事件 97.66%、备份恢复 95.75%，同源安装器 harness 99.67%；Java/Kotlin 96.11%，新宿主异常类 100%。这些是相应模块的行覆盖率，不是所有错误类别在真实设备上都出现过。

安装器 harness 使用同一份 C 源码及实际 curl/libarchive，替换固定 rootfs 大小、摘要和下载 URL 为小型本地归档及本地 HTTPS 服务。表内标为注入的函数另在此测试编译中替换；发行程序没有 TEST_* 故障开关，也没有把发行版安装限制改为测试数值。

## rish / Shizuku 实机补测

2026-10-08，使用 GitHub Release v0.6.3 的原始 `pdn` 与匹配的 `proot-loader`，通过一个持续的 rish 会话执行。执行身份为 Android `uid=2000(shell)`、SELinux `u:r:shell:s0`；程序从 `/data/local/tmp` 的独立测试目录运行，未使用 Termux 的 `$PREFIX`、下载器或 shell。BusyBox 仅用于构造测试锁及打包测试日志。

修正测试脚本的卸载确认参数、锁持有方式后，最终整组 **40/40 通过**。不支持创建 FIFO 的 shell 环境改用 `/dev/null` 检查不支持的 bind 来源类型；归档中的 FIFO 条目仍通过真实恢复操作验证。

| 实测内容 | 触发方法与核对结果 |
| --- | --- |
| 初始化与安装 | 版本输出为 0.6.3；官方源下载 Alpine 3.24.2 ARM64，完成校验、解压和安装；重复安装返回 `rootfs_exists` |
| 登录与命令 | 登录执行 `id -u`、`pwd`，分别得到 0、`/root`；exec 保留含空格和分号的单个参数，stdout 与 stderr 分开 |
| 工作区 | 宿主目录绑定到 `/workspace`，从 Linux 写入文件后在 Android shell 读到相同内容 |
| Linux 退出状态 | 正常退出为 success；退出 37 为 `guest_exit`，保留 `guest_exit_code=37`；SIGTERM 返回 255，事件明确记录 `guest_signal=15` |
| rootfs 与临时目录 | rootfs 不存在、目录路径实际为文件、chmod 000 后真实拒绝访问；显式临时目录不存在或为文件，分别返回具体目录错误与建议 |
| 参数与 bind | 未知发行版、未知镜像、不存在的 bind 来源、以 `/dev/null` 为 bind 来源，返回各自分类 |
| 配置与名称 | 无效或不存在的用户、损坏配置、配置符号链接、chmod 000 配置、仅大小写不同的双目录，分别验证用户、配置、权限及名称歧义分类 |
| 锁冲突 | 独立 BusyBox 进程持有实际 flock，确认就绪后运行安装、备份、恢复，三者均返回 `operation_busy`；符号链接锁入口返回 `lock_failed` |
| 备份恢复与卸载 | 真实 Alpine 备份、恢复、恢复后 exec、带 `--yes` 卸载均成功；原有备份内容未被覆盖，恢复目标确实被删除 |
| 归档错误 | 不存在的输入、`../` 路径、FIFO 条目、缺少 Linux 结构、9 GiB 声明头、非归档内容，分别返回文件缺失及具体归档分类 |
| 回滚与事件 | 失败后没有恢复或安装暂存目录残留，危险路径未创建外部文件；每项均核对递增 sequence、operation_id、唯一最终 result、实际退出码、outcome，错误含 message 和 suggestion |

本次覆盖 40 个操作场景、23 种具体错误码。JSONL 原始记录及 stdout/stderr 位于共享测试目录 `/sdcard/yyd/PDN/rish-v063/results.tar.gz`，逐项索引为同目录的 `manifest.tsv`。测试程序没有修改，未重建 APK。

这组测试证明的是 Android shell 身份下的发行 ELF 行为。Java 宿主异常继续以此前的 JVM 测试为依据，本次未重新运行 AAR App。网络失败细分仍以本地 HTTPS 测试及明确标注的故障注入为依据；存储满、只读挂载和内存耗尽未在设备上制造，不能算作此次 rish 实测。

## 0.6.4 启动错误验证

新增 `tests/test_pdn_startup.py`，执行 41 项启动测试。18 个文件系统、ELF、登录及受控 loader 场景分别运行于正常程序和链接替换测试程序；另外 5 个测试方法覆盖多个进程/追踪及提取失败条件。测试程序链接替换仅用于故障注入，发行 ELF 不含 `PDN_FIXTURE_*` 开关或替换函数。

| 验证内容 | 如何触发 | 性质 |
| --- | --- | --- |
| 初始 shell 缺失、不能执行、格式错误 | 删除 fixture 的 `/bin/sh`、移除执行权限、写入非 ELF 内容 | 真实文件及执行失败 |
| 坏 shell 回退后退出 0 | 无 shebang 的可执行文本包含 `exit 0`；最终仍为非零 manager_error | 真实执行回退；防止错误被成功退出遮盖 |
| ELF interpreter | 构造指向缺失、不可执行或损坏 interpreter 的 PT_INTERP；声明超过文件末尾的长度 | 真实 ELF 解析与文件失败，截断归为格式错误 |
| interpreter 读取 I/O | 测试链接替换让特定 PT_INTERP 的 pread 返回 EIO | 故障注入，原始 errno 保留 |
| 外部 loader | 指向缺失、不能执行或非 ELF 文件 | 真实执行失败 |
| 内嵌 loader 提取 | 仅匹配 loader 的 mkstemp、fchmod、ELF 数据 write，分别返回 ENOSPC、EACCES、ENOSPC | 故障注入；没有写满设备存储 |
| loader 的 open/mmap/close | 受控 ARM64 loader fixture 发起真实失败系统调用 | 真实内核错误＋受控 loader；不是设备随机出现的故障 |
| loader 没启动 guest 就正常退出 | 受控 loader 直接退出 0，不发出装载通知；最终为 guest_start_failed | 真实子进程退出；不伪造 errno |
| 实际交互登录 shell | passwd 选择不存在、不能执行或格式错误的 shell；包含文本回退退出 0 | 真实登录失败，包装层成功不等于登录成功 |
| 正常 Linux 退出 | 登录后退出 126/127、运行不存在命令；原有 exec 信号及退出码回归 | 真实 guest_exit，未归为启动错误 |
| 动态库搜索不能误报 | 复制真实 Alpine musl BusyBox/linker/libz 到私有 fixture，把依赖放在 `/usr/lib`，允许先探测缺失的 `/lib` 路径 | 真实 musl 登录；没有更改已有 Alpine 系统 |
| 进程与追踪启动 | 测试链接替换 fork、pipe2、ptrace TRACEME、SETOPTIONS、恢复执行，让它们返回指定 errno | 故障注入；验证非零退出、回收及不挂起 |
| Java 原生协议对接 | 实际原生缺失 shell 的结果由 PdnOperations 解析，核对类别、errno、建议及唯一最终结果 | 真实 native → Java 对接 |

动态 Alpine fixture 不存在时对应测试会明确跳过。本次环境中 fixture 可用，两次动态登录测试均执行通过。每个启动失败检查实际进程退出码与事件结果一致、sequence 连续、最终 result 唯一；已有具体诊断不会被后续通用错误覆盖。

本轮在 Termux 中执行 ARM64 Android 原生程序及 JVM 测试，没有重跑 rish 或独立 AAR App 的设备验收，也没有构建 App APK。0.6.3 的 rish 结果保留在上节。加载通知之后，动态链接器自己报告的共享库缺失或 guest 初始化脚本失败继续归为 guest_exit，从 stderr 读取详细原因；本轮不解析这些文案。

验证汇总：195 项 PDN 测试加 16 项 PRoot 回归，共 211 项原生测试，209 项通过、2 项因硬链接通道与 `/dev/full` 环境限制跳过；54 项 JVM 测试全部通过，6 项发布打包检查通过。AAR ZIP 完整，所含 pdn/loader 与对应本地 ELF 逐字节一致。

LLVM 行覆盖率：本轮新增或修改的原生可执行行 192/202（95.05%），事件模块整体 98.09%；Java/Kotlin 371/386（96.11%）。新增行统计合并正常程序及同源故障注入程序，测试替换函数不计入原生源码分母。它不代表整个 PRoot 引擎达到 95.05%，也不代表每种错误都在真实设备策略下发生过。子进程 `_exit` 分支通过测试程序的 profile 刷新替换收集覆盖率，发行程序保持 `_exit` 行为。
