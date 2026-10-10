# PDN 测试与版本验证记录

本文集中保存各版本的验证结果、环境、覆盖率和故障触发方法。README 与接入教程介绍使用方式；这里保留历史版本与实际产物的对应关系，不能把旧版结果当作新版实机验收。

## 验证结果总览

| 版本 | 验证范围 | 结果 |
| --- | --- | --- |
| 0.6.8 | 继承 statx SIGSYS 的入口参数恢复 | 原生 223 通过 / 2 跳过；5 组文件压力测试；修改行覆盖率 100%；AAR/APK 未重建 |
| 0.6.7 | 原始 ELF seccomp：Shell 加速及安全回退 | 原生 222 通过 / 2 跳过；路径 14/14；修改行覆盖率 85.71%；AAR/APK 未重建 |
| 0.6.3 | 原生错误分类、JVM、rish 实机补测 | 原生 168 通过 / 2 跳过；JVM 53/53；rish 40/40 |
| 0.6.4 | 启动错误、Release 原始 ELF、独立 AAR APK、直接 `.so` APK | 原生 209 通过 / 2 跳过；JVM 54/54；rish 28/28；AAR 19/19；`.so` 24/24 |
| 0.6.5 | 本地 AAR 独立 App：异步任务、配置、查询、双终端 | 实机 33/33；SDK 合并行覆盖率 90.42% |
| 0.6.6 | AAR 组件、Ubuntu 调试与三条路径实机验收 | SDK 90/90；打包 9/9；原生回归 16/16；rish 14/14；AAR 47/47；`.so` 38/38；MT 复现成功 |

两种独立 APK 在 Android 14（SDK 34）、targetSdk 35 的普通 `untrusted_app` 进程中验收。Release 0.6.4 的 APK 内 PDN/loader 与发布原件逐字节一致；0.6.5 属于本地构建验收；0.6.6 组件精简那一轮未重新验收 APK；后续 Release 原件实测见本文路径边界记录。

## 0.6.8 statx SIGSYS 与文件压力测试

MT 普通终端中的 Codex 安装反馈为 `tar ... Cannot open: Is a directory`。本轮在已有 Ubuntu 中对照发布的 0.6.6、原始 ELF 0.6.7 自动加速、0.6.7 禁用加速；三组直接创建普通文件和 GNU tar 解压小型归档均通过。不能把反馈直接判定为 0.6.7 的加速回归。

按提供的完整脚本，每组创建 200 个组目录、2000 个文本文件、50 个可执行文件和 50 个符号链接，再压缩、解压、比较目录、检查权限/链接和 SHA256 清单。脚本保存为 `scripts/test-pdn-filesystem-stress.sh`。检查输出使用 `grep -E 'FAIL:|tar:|RESULT:|Errors:' LOG | head -80`，并核对完整日志及实际退出码。

| 原始 ELF / 运行条件 | 错误数 | 文件数（源/解压） | 链接数（源/解压） | 结果 |
| --- | ---: | ---: | ---: | --- |
| 0.6.6 / Android shell | 0 | 2050/2050 | 50/50 | PASS |
| 0.6.7 / 自动加速 | 0 | 2050/2050 | 50/50 | PASS |
| 0.6.7 / 禁用加速 | 0 | 2050/2050 | 50/50 | PASS |
| 0.6.8 / 自动加速 | 0 | 2050/2050 | 50/50 | PASS |
| 0.6.8 / 继承 statx TRAP，完整追踪 | 0 | 2050/2050 | 50/50 | PASS |

另下载官方 Codex 0.162.1 ARM64 musl 完整包，核对官方 SHA256 后，使用 GNU tar 1.35 解压。0.6.6、0.6.7 自动加速/禁用三组均成功，核对 6 个文件内容摘要。仅解压与校验，不安装或运行 Codex。修复后的 0.6.8 在继承 statx TRAP 下也通过相同检查。

**已复现并修复的独立 bug**：测试启动器安装真实 BPF 过滤器，令 statx 触发 SIGSYS。修复前 0.6.6 和 0.6.7 都会对相对路径报 ENOTDIR；绝对路径也可能读到错误大小。ARM64 的 x0 同时表示第一个参数和返回值，SIGSYS 前的 syscall exit 会使 CURRENT 中的目录 fd 被返回值覆盖。完整追踪路径现在从 ORIGINAL 的入口快照读取 statx 参数。对于已启用加速、可能缺少有效入口快照的路径，保留原有选择，未扩展 guest 自行安装过滤器的支持。

新 probe 验证 AT_FDCWD、真实目录 fd、文件 fd + AT_EMPTY_PATH，核对类型、权限、大小及 24 字节内容，确认实际 SIGSYS、继承模式 2 和安全回退。正常 ELF 引擎 20/20；管理器回归 203 通过 / 2 跳过；LLVM 本轮修改的 2 行可执行 C 代码全部覆盖（statx 参数选择与 User-Agent），不代表整个引擎覆盖率。规格和质量评审通过。

本轮仍未在 MT 进程内直接复现原始 tar 报错；模拟一个 statx 限制不等同于 MT 的完整系统策略。原始反馈应在 MT 用新 ELF 复测，不能仅凭 Shell 通过就宣布彻底解决。AAR/APK 未重建，已发布 AAR 仍为 0.6.6；交付目录为 `/sdcard/yyd/PDN/v0.6.8/`。测试目录、下载归档、解压副本、编译夹具和原始覆盖率采集验收后清理，保留脚本、日志与摘要。

## 0.6.7 原始 ELF seccomp 加速

本轮只更新原始 ELF、匹配 loader 和改名后的 `.so` 副本；既有 AAR 和已发布 Release 保持 0.6.6，不代表普通 App 已获得加速。LD_PRELOAD 反馈仍只记录。

宿主 `PR_GET_SECCOMP == 0` 且不存在 `PROOT_NO_SECCOMP` 时尝试安装 PRoot 过滤器；已有过滤器、查询失败、显式禁用或安装失败继续完整追踪。Android shell 实测 `Seccomp: 0`，verbose 日志确认 `ptrace acceleration ... enabled`，不是仅凭版本输出中的编译能力判断。

| 检查 | 结果 |
| --- | --- |
| 引擎回归 | 19/19 |
| 管理器、事件、启动、配置、安装、归档与系统错误回归 | 203 通过，2 跳过 |
| Android shell 策略及功能 | 10/10：自动启用、禁用值 1/0/空值、继承过滤器、查询 EPERM、SIGSYS、安装、身份、文件读写删除 |
| 加速路径边界 | 14/14：guest /usr、嵌套 bind、路径组件边界、symlink、缺失目标等 |
| LLVM 修改行覆盖率 | seccomp 6/6 可执行行；计入未覆盖的 User-Agent 常量修改行为 6/7（85.71%），不代表整个引擎覆盖率 |
| 评审 | 规格及质量评审通过 |

性能对照使用同一候选 ELF、同一 Alpine 3.24.2 rootfs，在单个已连接的 Android shell 中交替运行自动加速和显式禁用，各 3 次。计时包含一次 `pdn exec` 及 guest 命令，排除 rish 建连、安装与下载。64 MiB 内容为零填充文件；压缩解压后核对 SHA256。文件任务验证 1000 个文件、读取最后一个文件并删除目录。

| 任务 | 自动加速中位数 | 显式禁用中位数 | 用时减少 |
| --- | ---: | ---: | ---: |
| 启动 `/bin/sh -c true` | 29 ms | 37 ms | 21.62% |
| 64 MiB tar/gzip 压缩解压 | 1706 ms | 3679 ms | 53.63% |
| 1000 个文件创建、检查、删除 | 2133 ms | 3403 ms | 37.32% |

这是候选内部加速/禁用对照，没有重新测试 Termux proot-distro，也没有测本轮 C 编译或 Claude 下载速度。样本较少、有调度与缓存波动，不能推广成所有任务或普通 App 的固定收益。普通 MT 终端若继承 Android seccomp 过滤器仍走兼容路径。

复测入口为 `scripts/test-pdn-seccomp.sh PDN LOADER ALPINE_ARCHIVE PROBE NEW_DIRECTORY`；probe 由 `tests/test_proot_nolib.py` 编译。脚本要求宿主无继承过滤器，自动加速未启用就失败。继承过滤器和查询失败由测试 probe 安装真实内核 BPF 过滤器触发，发行程序没有测试开关。路径测试沿用 `scripts/test-pdn-paths.sh`，本轮测试副本仅移除其强制禁用变量，并在运行前 unset。

LLVM 普通插桩在 child exec 后无法写出该子进程的计数，补采构建仅通过链接器 `--wrap=execvp` 在调用原函数前执行 `__llvm_profile_write_file()`。写出钩子仅存在于测试构建，正常发行 ELF 未链接该对象。最后按源行核对执行计数，原始采集缓存验收后删除；正常产物和覆盖率摘要保留。

交付目录 `/sdcard/yyd/PDN/v0.6.7/` 保存版本产物和报告，顶层原始 ELF/loader/`.so` 别名同步更新。未本机构建 AAR/APK，未发布新 GitHub Release。

## 事件与错误分类

事件协议使用 v1。`outcome` 表示最终结果，`code` 表示具体失败原因，`message` 与 `suggestion` 提供说明和建议。Linux 命令退出仍是 `guest_exit`，不因为退出码碰巧相同而变成管理器错误。

## 测试方法

- **真实失败**：实际执行文件操作、flock、curl 或 libarchive，用受控输入触发失败，再检查实际退出码、JSONL 最终结果、建议和回滚后的文件。
- **故障注入**：在原生 Termux 中编译并运行专用测试程序，让指定函数返回预设错误，检查错误分类、建议、退出状态和文件保留情况。锁错误的 9 个组合已验证通过；发行程序不包含注入开关。
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

日常验证 Release 时，不需要故障注入开关：通过缺失 rootfs、临时目录或 bind 来源等受控输入，检查真实错误分类和建议即可。内存分配失败、特定 errno 等难以稳定制造的情况由开发测试覆盖。故障注入验证处理分支，真实失败验证设备上的实际操作，两者分别记录。

在有源码、Python、Clang 和 NDK 的 Termux 中，可以运行单项注入测试：

```sh
NDK_PATH=/你的/NDK目录 CC=clang python tests/test_pdn_system_errors.py -v
```

脚本临时编译专用测试程序，把 `flock()` 替换为固定失败的函数，再自动检查结果和清理测试目录。测试变量只影响这份测试程序，对发行 PDN 无效。SSH 可作为进入 Termux 的入口，不能让发行程序获得这些注入开关。

原生测试需要 ARM64 Android 环境、可执行的 BusyBox fixture 与 NDK。常规入口为 `make test`；额外的错误处理测试位于 `tests/test_pdn_system_errors.py`。Java 入口为 `:proot-engine:testDebugUnitTest` 与 `:proot-engine:pdnCoverage`，真实原生对接测试还需设置 `PDN_NATIVE_FIXTURE`、`PDN_GUEST_FIXTURE`。

本环境缺少 `/dev/full`，因此真实 ENOSPC 测试跳过；硬链接事件通道测试也因文件系统拒绝创建硬链接而跳过。所有故障注入结果都不能替代独立 Android App 的实机覆盖率报告。

## 0.6.3 验证记录

原生 170 项执行，168 项通过、2 项按上述原因跳过；Java/Kotlin 53 项全部通过，真实 PDN/guest fixture 已启用。原 App Kotlin 编译、AAR ZIP 完整性、新异常类和原生程序逐字节一致性检查通过，没有构建原 App APK。

行覆盖率：前端 94.13%、配置 100%、卸载 93.84%、事件 97.66%、备份恢复 95.75%，同源安装器测试程序 99.67%；Java/Kotlin 96.11%，新宿主异常类 100%。这些是相应模块的行覆盖率，不是所有错误类别在真实设备上都出现过。

安装器测试程序使用同一份 C 源码及实际 curl/libarchive，替换固定 rootfs 大小、摘要和下载 URL 为小型本地归档及本地 HTTPS 服务。表内标为注入的函数另在此测试编译中替换；发行程序没有 TEST_* 故障开关，也没有把发行版安装限制改为测试数值。

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

以上验证在 Termux 中执行 ARM64 Android 原生程序及 JVM 测试，没有重跑独立 AAR App 的设备验收，也没有构建 App APK。0.6.4 的 rish 补测见下节。加载通知之后，动态链接器自己报告的共享库缺失或 guest 初始化脚本失败继续归为 guest_exit，从 stderr 读取详细原因；本轮不解析这些文案。

验证汇总：195 项 PDN 测试加 16 项 PRoot 回归，共 211 项原生测试，209 项通过、2 项因硬链接通道与 `/dev/full` 环境限制跳过；54 项 JVM 测试全部通过，6 项发布打包检查通过。AAR ZIP 完整，所含 pdn/loader 与对应本地 ELF 逐字节一致。

LLVM 行覆盖率：本轮新增或修改的原生可执行行 192/202（95.05%），事件模块整体 98.09%；Java/Kotlin 371/386（96.11%）。新增行统计合并正常程序及同源故障注入程序，测试替换函数不计入原生源码分母。它不代表整个 PRoot 引擎达到 95.05%，也不代表每种错误都在真实设备策略下发生过。子进程 `_exit` 分支通过测试程序的 profile 刷新替换收集覆盖率，发行程序保持 `_exit` 行为。

## 0.6.4 rish 补测

2026-10-09，通过 `sh ~/rish` 建立持续的 Shizuku shell 会话，以 Android `uid=2000(shell)` 执行 GitHub Release v0.6.4 的原始 `pdn` 和匹配的 `proot-loader`。部署后两份 ELF 的 SHA256 与下载文件一致；程序运行于 `/data/local/tmp/pdn-rish-v064`，rootfs、临时目录及工作区使用本次新建的独立目录，没有修改已有发行版。

**28/28 场景通过**，每个操作设有 20 秒超时，没有操作超时。

| 实测内容 | 场景数 | 触发及结果 |
| --- | --- | --- |
| 版本、安装、exec、login | 4 | 版本为 0.6.4；通过本地官方 Alpine 3.24.2 ARM64 归档安装；执行及登录成功，假 root 为 UID 0，工作区文件可从宿主读取，stdout/stderr 分开 |
| guest 退出 | 4 | exec 返回 127、SIGTERM；login 返回 126、127，均为 guest_exit，保留 guest_exit_code 或 guest_signal=15，没有启动错误码 |
| 初始 shell | 4 | 删除文件、移除执行权限、坏格式、无 shebang 文本回退退出 0，分别返回对应 guest_shell 分类；回退退出 0 仍为非零 manager_error |
| 外部 loader | 3 | 缺失、不可执行、坏格式，分别返回 proot_loader 分类与真实 errno |
| ELF interpreter | 4 | PT_INTERP 指向缺失、不可执行、坏格式文件，以及声明长度超过 ELF 末尾；分别返回 guest_interpreter 分类 |
| 实际登录 shell | 4 | passwd 选择缺失、不可执行、坏格式或文本回退退出 0 的 shell，返回 guest_login_shell 分类，不把包装 shell 启动成功当作登录成功 |
| loader 运行阶段 | 4 | 受控 ARM64 loader 发起真实失败 open/mmap/close，保留 EISDIR/EBADF；直接退出 0 而未启动 guest 返回 guest_start_failed，不伪造 errno |
| 动态库搜索 | 1 | musl 先探测缺失的 `/lib` 路径，再从 `/usr/lib` 找到 libz，交互登录成功，没有误报 loader 错误 |

28 份 JSONL 均检查 version=1、operation_id、连续 sequence、唯一且位于末尾的 result、实际退出码与 outcome 一致。19 个启动失败场景均有唯一 error、具体 code、message 和 suggestion；18 个含真实 errno，loader 直接退出的场景只报告真实退出状态。正常及 guest_exit 场景没有 error 事件或启动错误码。

原始事件、stdout/stderr 与工作区写入凭据位于 `/sdcard/yyd/PDN/rish-v064/results.tar.gz`，逐项索引为同目录的 `manifest.tsv`。本次安装使用预先下载的官方归档，没有重测在线下载。BusyBox 用于测试 fixture、超时及日志打包；PDN 没有依赖 Termux 的 `$PREFIX`、下载器或 shell。

本次证明 Android shell 身份下的发行 ELF 行为；没有重新验证 untrusted_app 的 AAR App，也没有在设备上制造 fork、pipe 或 ptrace 策略拒绝。这些分支继续以明确标注的故障注入测试为依据。测试文档更新不改变程序版本，没有构建 APK。

## 0.6.4 APK 接入实测

2026-10-09，分别构建并安装两个独立 Java Android App，在 Android 14（SDK 34）、ARM64 环境运行自动验收。两者均为 minSdk 28、targetSdk 35、compileSdk 36，使用 Android 平台控件；实际操作进程检查 SELinux 为 `untrusted_app`。rish 只负责安装、启动 instrumentation 与导出报告，不能代替 App 的执行身份。

| 路径 | 依赖与实测结果 |
| --- | --- |
| AAR | 独立 `examples/aar-probe` 仅导入 Release `pdn-engine-0.6.4.aar` 和 Kotlin 标准库，19/19 检查通过；Java API、事件回调与 AAR PTY JNI 均执行 |
| 直接 `.so` | 独立 `examples/so-probe` 不导入 AAR、Kotlin 或引擎源码模块，通过 ProcessBuilder 启动 Release ELF，自行读取 JSONL，并使用自有小型 PTY JNI；24/24 检查通过 |

两份 APK 均通过签名验证、ZIP 完整性、Manifest 的 SDK/入口/原生库解压检查。APK 内 `libpdn.so` 与 `libproot-loader.so` 和 GitHub Release 原件逐字节一致，原生运行依赖不含 Termux 库或 RPATH/RUNPATH；自有 PTY JNI 只依赖 Android libc/libdl，LOAD 对齐为 16 KiB。两份验收 APK 为 Debug 签名并带测试覆盖率采集，Release AAR 与 PDN/loader 本身没有重新插桩。

两条路径都实际点击初始化、官方源在线安装 Alpine、执行命令、打开终端、发送输入、resize、Ctrl-C、关闭及关闭后拒绝输入。完整验收另外创建新 rootfs，从官方 Alpine 3.24.2 ARM64 归档安装，验证 UID 0、精确 argv、stdout/stderr 分流、工作区持久化、真实 PTY、连续输入输出、`stty size` 为 32×96、正常退出及事件回调线程。

初始 shell 的缺失、不可执行、坏 ELF、无 shebang 文本退出 0，以及 passwd 选择的登录 shell 缺失、不可执行、坏格式，都通过私有 fixture 触发并验证具体错误码、真实 errno 与建议。无 shebang 回退退出 0 仍为 manager_error。loader 缺失和 App 数据目录中的执行拒绝也验证通过；后者实际为 EACCES，即使内容损坏也不能宣称覆盖 ENOEXEC。guest 退出 17、127 与 SIGTERM 均保留为 guest_exit。每次正常操作均检查连续 sequence、operation_id、唯一 started/result，manager_error 才有唯一 error。

直接 `.so` 工程额外通过 JNI 无效参数检查，以及真实 `/system/bin/sh` 构造的四类事件尾部错误：损坏 JSON、result 后还有事件、截断行、非法 UTF-8。它们验证宿主示例读取器会拒绝错误协议，不属于 PDN 原生故障注入。

| 实机行覆盖率 | 覆盖行 / 可执行行 | 比例 |
| --- | --- | --- |
| AAR 验证工程 Java | 425/454 | 93.61% |
| 直接 `.so` 验证工程 Java | 569/593 | 95.95% |
| 自有 PTY JNI | 92/103 | 89.32% |

Java 通过 JaCoCo 采集普通 App 的执行数据；自有 JNI 通过可选 LLVM 插桩构建采集，9/9 函数执行。这里统计验证工程的行覆盖率，不是 Release 引擎的覆盖率，也不代表所有内存耗尽或系统策略拒绝分支均实测。

验收脚本遇到两个宿主测试问题并已修正：归档 asset 的 `.gz` 后缀被构建工具解压并改名，改为 `.archive` 后核对 APK 内原始字节；Alpine `/bin/sh` 是 guest 绝对符号链接，检查入口改用 NOFOLLOW_LINKS，而实际可执行性由 guest exec 验证。它们没有改变 PDN 原生代码。启动 instrumentation 后还需显式打开 Activity，实际验收才继续；界面启动方式见两个示例工程说明。

测试输入的官方归档 SHA256 为 `9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773`；PDN 安装时继续按内置大小与 SHA256 校验。原始报告、Java `.ec`、JNI `.profraw` 和两份测试 APK 位于 `/sdcard/yyd/PDN/apk-v064/`，本地分析报告位于 `build/apk-v064/`。没有修改已有发行版，PDN 版本维持 0.6.4。


## 0.6.5 AAR 接口实测

2026-10-09，独立 Java App 仅导入本地构建的 0.6.5 轻量 AAR 与 Kotlin 标准库，在 Android 14（SDK 34）、ARM64、targetSdk 35 的 `untrusted_app` 进程中验收。rish 只负责安装、启动与导出结果。覆盖率 APK 和正式非插桩 APK 分别 **33/33 通过**，均验证实际 GUI 按钮；两份 APK 仅包含 PDN、loader、PTY JNI 三项原生文件。没有把本地构建的结果冒充 GitHub Release 原件的逐字节验收。

新增测试通过实际 API 触发行为：配置将含空格、引号、美元符号、中文与 emoji 的环境值原样传入 guest，检查自定义挂载文件；结构化查询检查已安装路径、未知版本字段和 Debian 单一官方源；两项异步命令检查独立 operation_id、指定 Executor 和唯一最终回调。取消与超时使用真的 guest shell 和后台 sleep，获取 PID 后检查两者均已消失，清理完成才结束等待。

两个真实 PTY 同时运行，验证不同 PID/fd、独立输入输出、TTY、resize、正常/非零/信号退出、重复关闭和关闭后输入失败。高层终端检查原生 shell 启动错误与事件回调一致；监听器抛出 Error 后检查回收与唯一失败通知。清空 ProcessBuilder 环境后，裸终端只收到显式变量；旧 launcher 继续继承宿主环境。JNI 错误参数、Unicode、exec/chdir 失败、旧接口适配与真实 errno 都有单独检查。

| 行覆盖率范围 | 覆盖行 / 可执行行 | 比例 |
| --- | --- | --- |
| 全部 SDK 类，JVM 单元测试 | 893/1044 | 85.54% |
| 全部 SDK 类，单元与实机合并 | 944/1044 | 90.42% |
| 引擎 PTY JNI，实机 LLVM 采集 | 269/292 | 92.12% |
| 本轮新增/修改原生可执行行 | 94/96 | 97.92% |
| AAR 打包脚本，Python 行追踪 | 51/53 | 96.23% |

JVM 90 项、PRoot 16 项、打包 9 项通过；原生 PDN 205 项中 203 通过、2 项按运行环境跳过。这里报告行覆盖率：JNI 分支覆盖率为 62.06%，没有宣称全部异常分支或所有 Android 系统都实测。终端 JNI 的可选插桩还采集 fork 子进程执行路径；正式 AAR 与交付 APK 均恢复为非插桩版本。

交付 APK、两种 AAR、33 项验收报告和说明位于 `/sdcard/yyd/PDN/apk-v065/`，本地覆盖率与构建记录位于 `build/apk-v065/` 和 `build/pdn-v065-coverage/`。正式 Maven 发布和 Release/R8 混淆验证不在本轮范围。

## 0.6.6 AAR 组件与打包验证

标准 AAR 与 lite 别名字节相同，原生文件仅含 PDN、loader、PTY JNI；全部 `PdnTerminal` 接口保留，终端界面由宿主提供。旧 pr 原生组件改由仓库原 App 单独打包。

- 打包回归 **9/9**，打包脚本行覆盖率 **51/55（92.73%）**。
- SDK 单元测试 **90/90**，行覆盖率 **893/1044（85.54%）**。
- 原生回归 **16/16**；实际 Debug AAR 的文件列表、终端类和别名字节一致性通过检查。
- Termux App 的旧组件 staging 和标准 engine staging 通过。标准 App staging 因缺少 vendor termlib 指定的 NDK 27.0.12077973 未执行；标准分支只完成相关路径与过滤规则的代码审查。

本轮未构建 APK。上述结果不代替 0.6.6 的独立 App 实机验收。

## 0.6.6 Ubuntu 调用 Android 命令验证

2026-10-09，ARM64 Android、PDN 0.6.6、Ubuntu Base 24.04.5 LTS：从 Shizuku 获得真实 `uid=2000(shell)` 后启动 Ubuntu，Android 属性查询、包查询和系统设置读写通过，并在 MT 管理器中复现。

| 检查 | 结果 |
| --- | --- |
| 原始 ELF 的 `version` 与 `--version` | 均为 0.6.6 |
| Ubuntu 下载、SHA256 校验和安装 | 通过 |
| guest 模拟 root 与真实 Android 身份 | guest 显示 root，真实 UID 仍为 shell |
| 绑定 `/system`、`/apex` 与 linker 配置后调用 Android 命令 | `getprop` 返回 SDK 34，`cmd package path android` 返回 framework 路径 |
| Ubuntu 内写入测试设置，退出后从 Android shell 核对 | 值一致；删除后返回 `null` |
| 自定义 `rish()` 包装函数 | `-c`、交互、空格和引号参数、保存 `.bashrc` 后重新登录均通过 |
| MT 管理器复现 | Ubuntu 内 Android 命令及设置验证通过 |

该函数通过继承的 shell 权限执行 Android 命令，不再次连接 Shizuku。guest 内再次启动原始 Shizuku 客户端未完成稳定连接验证；Linux 独立 adb 客户端和 Android 真 root 不属于本次通过范围。已授权宿主退到后台时出现断连，电池“无限制”未解决；操作教程要求全程保持前台。

独立 AAR App 的早期手动验收还完成了 `apk add nano`，并核对正常 APK 的签名、Manifest 和原生文件摘要。GUI 安装 curl 后的 HTTPS 访问也已验证。详细操作见 [Android shell 教程](pdn-shizuku-android-shell.md)和 [AAR 示例](../examples/aar-probe/README.md)。


## 0.6.6 路径边界与三种接入方式实测

2026-10-10，使用 GitHub Release 0.6.6 原件，在 ARM64 Android 14（SDK 34）完成路径边界验收。原始 ELF 通过 rish 在真实 Android shell 身份下执行；两个独立 APK 在普通 `untrusted_app` 身份下执行，minSdk 28、targetSdk 35。AAR App 仅依赖发布 AAR 和 Kotlin 标准库；直接 `.so` App 使用发布 ELF、自有事件与 PTY 适配器。两个 APK 内的 PDN 与 loader 均与 Release 原件逐字节一致。

| 接入路径 | 新增路径用例 | 完整验收 |
| --- | --- | --- |
| 原始 ELF / Android shell | **14/14** | 本轮专门执行路径用例 |
| AAR 独立 APK | **14/14** | **47/47**，包含原有接口、GUI 与终端验收 |
| 直接 `.so` 独立 APK | **14/14** | **38/38**，包含原有进程、事件、GUI 与终端验收 |

| 用例 | 结果 |
| --- | --- |
| guest `/usr` 读写映射 | guest 标记读写正确，隔离的宿主 `/usr` 夹具标记保持不变 |
| 父 bind 在前、子 bind 在前 | 两种参数顺序均优先匹配更深目录；写入实际子 bind 来源 |
| 路径组件边界 | `inner` 的绑定不覆盖 `innerish` |
| 绝对、相对 symlink | 目标在 guest 路径内解析，读写目标正确 |
| 跨 rootfs 链接，有显式 bind | 可读写另一个 rootfs 的指定目标 |
| 跨 rootfs 链接，无 bind | 不会自动访问另一个 rootfs |
| 链接直接指向另一 rootfs 的宿主绝对路径 | 没有对应 bind 时读取失败，目标保持不变 |
| 断链创建目标 | 写入链接会创建 guest 目标，链接本身保留 |
| symlink 循环 | 读取失败，guest 能正常结束 |
| 不存在的文件 | 读取失败，不创建文件 |
| 父目录存在、目标文件不存在 | 创建成功，落在 guest rootfs |
| 父目录也不存在 | 创建失败，不生成意外宿主文件 |

负向用例先执行正向读取，确认 guest 和读取工具可运行。两个 App 每项还核对结构化成功结果、唯一完成标记及宿主文件状态，避免把启动失败当作路径测试通过。App 用例使用独立夹具；Shell 用例使用全新目录，不操作已有发行版。

实机 Java 行覆盖率：AAR App **768/815（94.23%）**，直接 `.so` App **667/703（94.88%）**；两份新增路径测试类均为 **105/109（96.33%）**。这是验证 App 的覆盖率，不是 PRoot 路径转换代码的覆盖率。

宿主 `/usr` 对照使用专门的可写夹具，不修改 Android 或 Termux 的真实系统目录。这些结果验证路径映射正确性，不代表 PRoot 是安全沙箱；显式 bind 的目录仍可修改宿主文件。

复现入口：`scripts/test-pdn-paths.sh` 接收 PDN、loader、官方 Alpine 归档和不存在的新目录；两个 App 的“全部验收”包含同一组路径场景。报告、APK 与日志位于 `/sdcard/yyd/PDN/path-boundary-v066/`，本地覆盖率位于 `build/path-boundary/`。


## 0.6.6 Alpine 启动耗时对比

2026-10-10，同一 ARM64 Android 环境，使用同一份 Alpine 3.24.2 官方归档。计时从调用启动器开始，到 guest 执行 `printf PDN_BENCH_READY` 并退出结束；包括启动器、PRoot、guest shell 和进程回收，不包含 rish 连接、下载、安装、终端界面绘制。

| 环境 | 样本数 | 中位数 | 范围 |
| --- | --- | --- | --- |
| 原 Termux + proot-distro 5.9.0 | 40 | **272.2 ms** | 191.3–286.1 ms |
| 清空环境变量、新 HOME + proot-distro 5.9.0 | 20 | **268.6 ms** | 263.4–274.8 ms |
| 相同干净 Termux + PDN Release 0.6.6 | 20 | **35.5 ms** | 30.4–66.0 ms |
| Android shell + PDN Release 0.6.6 | 40 | **40.9 ms** | 31.3–74.9 ms |

主要两组交换顺序各测 21 次，每批首测单独记录，余下共 40 次用于统计。首次观测：proot-distro 两批为 692.2 / 709.8 ms，PDN Android shell 为 42.5 / 104.3 ms。这些不是清空系统缓存后的严格冷启动。环境清理对照各测 21 次，去掉首测后统计 20 次。

清理使用 `env -i`，仅提供 PATH、HOME、TMPDIR、PREFIX、TERMUX 路径、LANG 与 TERM；PDN 另提供其 rootfs、loader、临时目录和 seccomp 配置。新 HOME 不读取原终端配置，去掉 LD_PRELOAD、LD_LIBRARY_PATH、ENV、BASH_ENV 等继承变量；使用新安装的测试 Alpine，不修改原发行版或 Termux 配置。它隔离环境变量和 HOME，不是重新安装一个全新的 Termux。

本次 proot-distro 普通与干净环境相差约 **3.7 ms**，不足以解释它与 PDN 的约 **230 ms** 差距。同样干净的 Termux 中 PDN 为约 35 ms，说明快速启动不要求 rish。已安装的 proot-distro 5.9.0 使用 Python 入口，源码显示入口导入多个命令模块；启动器和默认挂载配置是合理的差距来源，但本次没有逐项剥离其开销，不把差距归因于 PRoot 引擎本身。

proot-distro 使用默认登录配置，PDN 使用默认登录配置；两者挂载和扩展项不完全相同。这是实际入口的启动成本对比，不是相同 PRoot 参数下的引擎微基准，也不代表 GUI 打开速度或 Linux 长任务性能。

复现计时脚本：`scripts/benchmark-pdn-startup.sh OUTPUT_CSV REPETITIONS COMMAND [ARGS...]`。两边均用同一个 Android `date` 纳秒计时脚本，包含少量相同的采样开销。原始 CSV 与汇总位于 `build/startup-benchmark/`，交付副本位于 `/sdcard/yyd/PDN/path-boundary-v066/`。


## 0.6.6 短、中、长任务实测

2026-10-10，干净 Termux 的 proot-distro 5.9.0 与 Android shell 的 PDN Release 0.6.6，使用同一 Alpine 3.24.2 ARM64 归档、相同源和完全一致的 guest 包版本。两边 `apk update` 和安装 curl、nano、Python、GCC、musl-dev、make、CA 证书均成功；共 **9 类任务，两边全部通过**。

计时在已进入 Linux 的 Python 中使用单调时钟，排除启动器、rish 连接和 SSH 建连。所有程序使用 guest 绝对路径，PATH 不含 Termux 程序目录；避免把宿主 curl/nano 当成 Alpine 程序。所有读写删除仅作用于新建的 `/tmp/pdn-workloads-*` 夹具，结束后回收。

| 任务 | 样本数 | Termux proot-distro | Android shell PDN | 结果 |
| --- | --- | --- | --- | --- |
| 写入、读取、rm 删除 | 15 | 13.8 ms | 42.8 ms | 通过 |
| apk 查询已安装软件 | 10 | 45.1 ms | 101.6 ms | 通过 |
| apk add 已安装软件 | 5 | 615.8 ms | 1124.8 ms | 通过 |
| nano 输入、保存、读取、删除 | 3 | 465.9 ms | 552.5 ms | 通过 |
| curl HTTPS 请求 | 3 | 330.0 ms | 585.3 ms | 通过 |
| 64 MiB tar/gzip 压缩解压 | 6 | 2110.9 ms | 3374.3 ms | 通过 |
| 512 MiB SHA256 计算 | 3 | 715.2 ms | 710.8 ms | 通过 |
| 3000 文件写入、stat、读取、删除 | 3 | 8878.7 ms | 9555.6 ms | 通过 |
| 25 个 C 文件编译、链接、运行 | 6 | 3650.5 ms | 7105.8 ms | 通过 |

表中为中位数。压缩与编译先按 Termux→PDN 测试，再交换顺序复核，各合并 6 个样本；其他任务按表中次数测试。未控制 CPU 频率与系统负载，因此不作为所有设备的性能保证。

- nano 使用真实 PTY 输入文字，Ctrl-O/Enter 保存，Ctrl-X 正常退出；核对保存字节，再使用 guest cat 和 rm。其时间含自动化固定等待，仅用于功能验收，不比较编辑器响应速度。
- curl 从 Linux 内访问 `https://example.com/`，HTTP 200、TLS 校验为 0，响应内容正确。它包含网络波动；在线 apk 下载同样不用于判断管理器性能。
- 压缩夹具是循环字节组成的 64 MiB 可压缩数据，解压后核对 SHA256；不代表一般文件压缩性能。
- 哈希处理 512 MiB，与计时前计算的预期摘要比较，计算性能基本持平。
- 批量文件每次创建 3000 个文件，逐个 stat 和读取核对，然后真实递归删除。
- C 工程包含 25 个编译单元，每轮编译、链接并执行生成程序，检查计算结果。

**启动优势不等于持续执行优势。** 本次默认配置下，PDN 的压缩、编译和部分短任务更慢；纯哈希计算接近。

进一步对照只给 Termux 的 proot-distro 设置 `PROOT_NO_SECCOMP=1`：压缩中位数 **3338.3 ms**、编译 **6880.3 ms**，接近 PDN 的 **3374.3 / 7105.8 ms**。PDN 管理器默认关闭 PRoot 自带 seccomp 加速；该项配置是这两个场景差距的重要原因。它不同于 Android zygote 的 seccomp 限制，本轮没有修改发行程序或 AAR 的默认策略。

guest 包版本包括 curl 8.22.0-r0、nano 9.2-r0、Python 3.14.8-r0、GCC 15.2.0-r5、musl 1.2.6-r2。复现脚本 `scripts/test-pdn-workloads.py` 支持 `--route`、`--report`，以及选定任务的 `--only`、重复次数 `--repetitions`。报告与日志位于 `build/task-benchmark/`，交付副本位于 `/sdcard/yyd/PDN/task-benchmark-v066/`。

单独使用 Python trace 验证测试脚本行覆盖率 **185/196（94.39%）**，9 类任务均通过；插桩结果不计入上述性能对比，也不代表 PRoot 引擎覆盖率。

补正计时脚本：Android mksh 的整数运算会在长于约 2.1 秒的纳秒差值上溢出。外部计时改用 Android `expr` 做 64 位减法，3 秒任务回归通过；前面的启动样本均不足 1 秒，不受此问题影响。本轮任务计时使用 guest 单调时钟，在线安装只记录成功，不比较其下载耗时。

## 0.6.6 原生 Termux 故障注入测试

2026-10-10，在原生 Termux 环境运行 `tests/test_pdn_system_errors.py`，使用 Python、Clang 和 NDK 临时编译专用测试程序，退出码为 0。

**1 个测试方法、9 个组合通过**：ENOLCK、EIO、EINTR 三种锁错误，分别覆盖 exec、config、uninstall；核对 `lock_failed`、原 errno、建议和 rootfs 保留。临时测试目录自动清理，发行程序没有被修改。

日志位于 `build/task-benchmark/ssh-lock-harness.log`。

## 0.6.6 Release/R8 混淆验收

2026-10-10，两份独立 probe App 启用 R8 代码混淆、优化及资源压缩，使用 Android 默认优化/JNI 规则，没有保留整个 SDK 的附加规则。APK 为非 debuggable 的 Release 构建，使用本地 Debug 测试密钥签名，用于验收和演示，不是生产签名。AAR 接入使用已有 Release 0.6.6 AAR；没有改动 PDN 或重新发布引擎。

| 接入 | App 版本 | R8 实机结果 | 实际混淆 |
| --- | --- | --- | --- |
| AAR | 0.1.4 / code 5 | **47/47** | 32 个引擎 SDK 类重命名，例如 PdnConfiguration、PdnCatalog |
| 直接 .so | 0.1.2 / code 3 | **39/39** | NativeRuntime、NativeOperations、ProbeTerminal 等类重命名 |

测试在普通 App 进程中运行，覆盖 GUI 初始化/安装/命令/终端输入与 resize/关闭、事件和路径边界；AAR 还覆盖配置、查询、异步取消/超时及双终端。两份结果均为 `passed=true`、`gui_controls_tested=true`，instrumentation 最终状态为 `INSTRUMENTATION_CODE: -1`。Release 不能使用 run-as，完整 JSON 报告从 instrumentation 输出读取。

### 本轮发现并修复

直接 .so 示例旧 PTY 适配器依赖 `/proc/<pid>` 判断子进程状态；非 debuggable Release 的存在性检查失败，导致 GUI 无法打开终端。原 waitPid 用 0 同时表示运行中和成功退出，也需要这项额外判断。修复为通过 waitpid 返回运行中 -2、错误 -1、正常退出码或 128+信号，重试 EINTR，并在 Java 会话中缓存完成状态。移除 /proc 依赖后，Release 完整验收通过。

新增一项实际子进程回归：用输入闸门保持 Android shell 子进程运行，再验证退出 0、退出 37、SIGTERM 和已回收后的错误。它使用真实 waitpid，不是故障注入。修复仅涉及直接 .so 示例适配器；AAR 原有独立会话等待机制未修改。

本次修复后另构建带采集的 Debug App，39/39 通过。Java 行覆盖率 **695/731（95.08%）**，NativePty 91.67%、ProbeTerminal 96.74%、ProbeSuite 97.08%；PTY JNI **94/105（89.52%）**。采集构建用于覆盖率，不作为最终交付；最后恢复无插桩 Release App 与原生输入。AAR SDK 本轮未改动，沿用此前超过 80% 的覆盖率记录。

### 构建与产物检查

两份 APK 的 ZIP、签名、包名、minSdk 28、targetSdk 35、非 debuggable、原生库解压配置均通过。PDN/loader 与原始 Release 逐字节一致，AAR PTY JNI 与 AAR 内原件一致，直接 .so PTY JNI 与本次无插桩示例构建一致。最终 APK 不含 JaCoCo 或 LLVM 覆盖率采集组件。R8 mapping、configuration、usage、构建日志及报告保存在 `build/r8-acceptance/`；本地产物和记录位于 `/sdcard/yyd/PDN/r8-v066/`。

本轮验证宿主 Release/R8 接入，不把 AAR 的 Debug 构建类型误写为新发布了 Release AAR，也不代表 Maven 发布、所有 ROM 或任意反射式接入都已验收。具体构建、测试签名和报告读取方法见两份 probe README。
