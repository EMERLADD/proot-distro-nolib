# PDN 工作区嵌入与设备本地更新需求：代码核对修订版

日期：2026-10-07

基线：当前项目源码、独立 pdn 0.6.0，以及本次讨论形成的原 MD。

状态：本文保留讨论时的功能盘点；后续 W1 已接入示例 App，当前用法见 [Android 接入教程](android-embedding.md)。其他条目仍作为后续范围。

## 1. 项目目标与核对口径

两个使用场景保持不变：

1. 非 Termux Android App 打包原生 pdn，为 AI 前端等应用提供 Linux 工作区。
2. 玩家在 MT 管理器等适合执行原生程序的宿主中使用 pdn，可选地在设备本地更新 PRoot。

不要求 100% 复刻 proot-distro。普通运行不依赖 Termux；玩家更新所需构建工具属于可选依赖，也不强制要求安装 Termux。guest 使用自身的 shell 和工具不属于宿主依赖。

本次明确区分三个层次：

- **独立 pdn 已实现**：当前 C 管理器和它编译进来的 PRoot 引擎实际支持。
- **仓库其他组件已有**：Rust pr-cli、Android App/JNI 或构建脚本已有实现，但不代表独立 pdn 已接入。
- **确实待新增**：代码中没有对应完整能力，需要新增或补齐。

不能把“还没有统一的 App API”写成“底层功能不存在”，也不能把旧组件存在的功能直接算成独立 pdn 已支持。以下源码路径均相对于仓库根目录，便于将本文件单独保存和阅读。

## 2. 原文中需要纠正的主要条目

| 原文范围 | 核对后的实际状态 | 修订后的剩余工作 |
| --- | --- | --- |
| W1 宿主路径、无固定包名 | 独立 pdn 已支持参数/环境变量指定路径，相对路径可从 cwd 解析 | 文档说明及可选 App 封装接入，不重做路径机制 |
| W1 参数数组和子进程 | pdn 保留 argv 边界；JNI 已 fork；Kotlin 已有 startCustomSession(List) | 收敛旧字符串切分入口、接入 pdn |
| W1 Kotlin 封装、示例 App | 仓库已有 Android library、App 和终端 UI | 迁移现有接入并完善示例，不从零创建整套 App |
| W3 stdin/stdout/stderr、退出码 | pdn exec 已继承标准流并传递退出状态 | 宿主任务句柄、异步流、取消与统一状态 |
| W4 PTY 窗口调整 | Session.resize/JNI ioctl 已实现 | 保留并验证，不重新开发 resize |
| W5 创建、查询、删除、多实例 | install/list/remove 已实现；restore 可用新名字创建另一实例 | 安装别名、稳定元数据、直接 clone/rename |
| W5 活动会话锁、暂存隐藏 | 已有共享/独占锁；隐藏暂存目录不会被 list 列出 | 新增操作接入现有规则 |
| W6 默认配置和覆盖规则 | config 已保存、展示、清除；调用参数已有覆盖/追加语义 | 统一协议和可选环境继承策略 |
| W6 挂载检查、环境显式添加 | parse_bind 与 --env 已实现 | 扩展协议错误分类，无需重复实现基本校验 |
| W7 假 root、loader、内核、链接兼容 | pdn 登录已统一组装；引擎适配已经存在 | 新兼容配置接入、回归与 App 场景验证 |
| W7 /dev/shm、/dev/fd、部分 /proc 适配 | Rust pr-cli 已有对应启动配置 | 评估迁移到 C pdn，不能称整个仓库没有 |
| W8 下载进度、取消、镜像回退、发布前隐藏 | 独立 pdn 已实现 | 结构化进度、操作查询、安装元数据及异常恢复 |
| W9 备份取消、链接迁移、暂存恢复 | 已实现；恢复只允许新目标且拒绝覆盖 | 进度/FD 接口和快照清单；原位替换属单独增强 |
| W10 OCI | Rust 已有解析、架构选择、摘要校验、层/whiteout、安装记录等实现 | 评估移植并验证边界，独立 C pdn 尚未接入 |
| U3/U4 本地构建、静态依赖、匹配 loader | 已有独立构建脚本和输出 | 非 Termux 设备工具链、候选版本流程 |
| U5 ELF 检查/TLS patch | 独立构建已有依赖/RPATH/路径检查；历史脚本已有 TLS patch | 整合增强检查和玩家可用修补入口 |
| U8 SHA256、版本和源码许可证材料 | 发布脚本已生成 | 增加上游/补丁/工具链/测试记录 |

## 3. 工作区嵌入：已有与真正待做

### W1. 打包与初始化

**已有：**

- pdn 使用 PDN_ROOTFS_DIR 或 HOME 定位系统，支持 --rootfs PATH；绑定来源和 rootfs 相对路径可通过 cwd 解析。使用 $PWD 设置这些值即可，默认 rootfs 路径仍是 $HOME/.local/share/pdn/rootfs。
- 临时目录使用 PROOT_TMP_DIR、TMPDIR 或 rootfs 下 .pdn-tmp；外部 loader 使用 PROOT_LOADER，未指定时有内嵌 loader 路径。
- 独立 pdn 不推导固定 App 包名；项目目录可用 --bind 指定。
- argv 边界通过 guest shell 的 exec "$@" 保留。
- Android library、ProotHost、ProotLauncher、JNI fork/PTY、nativeLibraryDir 获取和示例 App 已存在。
- libproot.so、libproot-loader.so 等可执行程序的 APK 打包方式已经存在。

**待做：**

- 把独立 pdn 产物接到 APK 构建与现有启动层，新增/配置 libpdn.so；旧启动层主要仍调用 pr-cli。
- 复用 startCustomSession 的数组接口，收敛 runCommand 的字符串空格切分入口。
- 将现有路径参数记录为宿主接入契约，按需添加 Kotlin 适配，不新增强制性的另一套路径机制。
- 复用现有 App 验证 pdn 集成、换包名/目录和真实 App 进程启动。

依据：src/proot/src/cli/pdn.c 的 pdn_rootfs_base、parse_bind、pdn_login；src/proot/src/execve/enter.c；android/proot-engine 下 ProotHost.kt、ProotLauncher.kt、ptyjni.c；android/app 下 App.kt。

### W2. 机器结果与事件协议

截至 PDN 0.6.3，下列原规划已实现：协议 v1、操作 ID、阶段与进度、错误类别和建议、最终结果、guest 状态区分、独立事件通道及 Java/Kotlin 回调。管理和宿主错误细分已补充，具体范围与测试方法见 [事件 API](pdn-events.md) 和 [错误验证](pdn-error-testing.md)。以下保留最初的范围说明。

**已有：**

- 管理命令已有退出码；安装/归档取消使用 128 + 信号号，执行返回 guest 状态。
- config --show 已输出 JSON；这不是所有操作通用的事件协议。
- 下载已有进度回调和文字阶段提示。

**已完成：**

- [x] 为需要宿主解析的操作定义协议版本、操作 ID、阶段、稳定错误分类和最终结果。
- [x] 对现有退出码提供解释；guest 非零退出与管理器失败用独立元信息区分，不占用 guest 退出码空间。
- [x] 结构化输出安装/备份事件，并与 guest 原始 stdout/stderr 分开传递。
- [x] 明确事件节流、完成语义和日志中敏感值的处理。

依据：pdn_config.c 的 pdn_options_show；pdn_install.c 的 progress/pdn_install；pdn_backup.c 的 pdn_archive。

### W3. 任务执行与回收

**已有：**

- exec/login 支持参数数组、用户、工作目录、环境变量、stdin/stdout/stderr 和退出状态。
- 登录默认 --kill-on-exit，主命令结束时已有 guest 后台进程清理机制。
- JNI 提供 fork 与 waitpid；App 的部分非交互管理调用已用 ProcessBuilder 启动并等待。

**待做：**

- 增加每任务独立句柄、状态、进程身份、关联工作区/引擎版本，以及查询/等待/取消接口。
- 宿主侧管理独立 stdout/stderr、stdin 写入/关闭、持续排空输出、缓冲/日志上限和超时。
- 取消结合 PRoot 信号语义终止被跟踪会话并回收，不能只依赖终端关闭。
- 修复 JNI 全局 last_child_pid 不能表达多个并行任务的问题。
- 修复 nativeWaitPid 的 0 同时表达运行中和退出码 0 的问题，保留信号退出信息。
- 若保存任务记录，增加重连、失效判断和 PID 重用校验。

依据：pdn.c 的 pdn_login；tests/test_pdn.py 的输入输出/退出测试；ptyjni.c；App.kt/MainActivity.kt 的 ProcessBuilder 调用。

### W4. 执行模式与宿主生命周期

**已有：**

- JNI PTY 创建、读取、写入、resize、FD 关闭，以及 Kotlin Session 和终端 UI。
- 普通外部程序可通过系统进程管道调用；现有 App 的管理调用使用合并 stdout/stderr 的文字流。

**待做：**

- 给 pdn 提供明确的非 PTY 任务接口，分别保留标准流和结构化状态。
- 保留现有 resize，补齐每会话身份、退出通知和与 W3 相同的取消语义。
- 区分关闭输出连接、关闭 stdin 与结束任务；Session.close 的 Ctrl+D/关 FD 不是可靠取消。
- 可选后台会话增加日志、查询、停止和重连；宿主负责 Android 生命周期策略。
- 审查现有 fork 后调用 JNI/JVM 的路径，将启动参数和环境准备前移；这是现有启动层的修正，不是重新开发 PTY。

依据：ProotLauncher.kt 的 Session；PtyNative.kt；ptyjni.c；TerminalActivity.kt。

### W5. 多工作区、模板和元数据

**已有：**

- install/list/remove；按名字或路径登录；手工 rootfs 可直接识别。
- restore NEWNAME ARCHIVE 能创建另一份实例，因此“完全不能多实例”不成立。
- 登录/执行持有共享 rootfs 锁，删除和配置修改等使用排他锁；备份持有排他锁。
- 安装/恢复在隐藏暂存目录完成，再发布；list 的名称规则排除这些暂存目录。
- 备份恢复已迁移 PRoot 内部硬链接模拟关系。
- Rust pr-cli 有 rename 和 OCI 安装元数据，但 rename 不能据此认定已完整处理所有 .l2s 迁移问题。

**待做：**

- install 支持实例别名，分开发行版来源与实例名称；新增稳定 ID、显示名和来源等元数据。
- 用现有归档迁移逻辑组合直接 clone；为 rename 实现并验证路径/链接一致性。
- 新操作使用已有锁，不重新设计基本互斥机制。
- 可选地规定项目文件独立于 rootfs 保存并绑定；现有 --bind 已能做到，缺的是宿主约定和管理接口。

依据：pdn.c 的 list/pdn_login；pdn_remove.c；pdn_backup.c；src/pr-cli/src/commands_extra.rs 的 command_rename；install_model.rs。

### W6. 挂载与执行配置

**已有：**

- config 保存/查看/清除，--no-config 恢复入口，原子写入和格式检查。
- 单次用户/工作目录/环境覆盖默认值，绑定追加且后续绑定可覆盖同一目标；重复环境变量最后一次赋值生效。
- 挂载来源 realpath、类型检查、目标绝对路径和语法校验；启动时重新验证已保存绑定。
- --env 显式设置变量；清除 LD_PRELOAD、LD_LIBRARY_PATH、ENV、BASH_ENV，设置 guest 常用环境。

**待做：**

- 将现有规则写入机器接口，按需要增加错误分类和最终启动配置检查。
- 若宿主需要，新增环境继承白名单/清空模式；当前仍继承其他宿主变量。
- 项目目录和共享目录的授权由宿主控制；可选输入副本策略复用复制能力。

PRoot 绑定不构成可靠安全隔离或只读权限。原文中对应说明是行为边界，不是一项需要实现的新功能。

依据：pdn_config.c 的 pdn_option_add/pdn_options_save；pdn.c 的 parse_bind/pdn_login；tests/test_pdn_config.py。

### W7. Linux 运行兼容性

**已有：**

- C pdn 登录集中组装假 root、身份、loader/临时目录、link2symlink、内核版本和 kill-on-exit。
- 引擎已有 Android SIGSYS、ARM64 原始寄存器、clone/vfork、readlink 与身份模拟等适配。
- 引擎已有 SysV IPC 等扩展入口，不是从零开发引擎。
- Rust shared.rs 已有 /dev/shm、/dev/fd、/dev/random 和部分受限 /proc 数据的绑定配置。
- 仓库已有包管理、Git、GCC/Rust 等测试及历史验证材料，不能写成尚未有任何工具测试。

**待做：**

- 为 C pdn 迁移/改造需要的 /dev/shm、设备别名及受限系统数据处理；检查路径和目录创建边界。
- C pdn 登录接入 SysV IPC 开关并验证行为，当前启动组装没有默认开启。
- 在新启动策略和真实宿主下回归已有用例；按目标 AI 工作流补充 Python/Node.js/工具验收。

依据：pdn.c 的 pdn_login；src/proot/src/cli/proot.h；src/pr-cli/src/shared.rs 的 build_proot_args；docs/integration-tests.md 和 tests/。

### W8. 安装操作与诊断

**已有：**

- HTTPS、大小和 SHA256 验证，证书检查，多源固定顺序回退、手动镜像选择、连接/总耗时/低速限制。
- 下载百分比回调，校验/解压/初始化阶段文字提示。
- SIGINT/SIGTERM 取消、常规失败和中断时清理暂存、安装锁、拒绝覆盖已有目标。
- 新实例完成之前不发布，可用列表不显示隐藏暂存目录。

**待做：**

- 将现有回调和阶段转换成结构化事件；增加解压条目/数据进度和 App 操作查询。
- 持久保存来源、摘要和版本；支持实例名称。
- 增加空间预检和更明确的空间不足错误；不能据此说当前完全不处理写入失败。
- 增加 SIGKILL/掉电后暂存清理或恢复入口，以及诊断接口。
- 如需要，增加有界的同源重试和可验证断点续传；现有镜像回退已经有界，不能重做成“新增回退”。

依据：pdn_install.c 的 download/download_mirrors/progress/pdn_install；tests/test_pdn_install.py。

### W9. 文件交换、备份和快照

**已有：**

- C pdn backup/restore，tar/gzip 输入，gzip 输出，内部链接迁移、权限和数据保留规则。
- 备份/恢复锁、取消处理、失败清理、暂存和不覆盖发布。
- 已排除宿主配置、loader 临时目录和运行时内容；外部挂载内容不自动打进备份。
- Rust pr-cli 有 copy，依赖外部 BusyBox cp；它不是 C pdn 的原生文件 API。

**待做：**

- 独立 pdn 增加原生导入/导出/递归复制，明确 guest 路径及链接语义。
- 扩展归档外部入口接受 FD/流；当前内部虽然使用 FD，用户接口仍只接受文件路径。
- 为现有备份取消机制提供宿主入口，增加进度事件、快照清单和摘要记录。
- 明确项目目录是否另行打包；恢复后由宿主重新配置挂载。
- 默认保留“恢复到新目标”的现有策略；如确实需要原位回滚，再单独实现替换事务及失败恢复，不将它冒充已有恢复的缺陷。

依据：pdn_backup.c 的 pack/unpack/pdn_archive；tests/test_pdn_archive.py；commands_extra.rs 的 command_copy。

### W10. OCI：按需接入其他已有组件

Rust pr-cli 已有镜像引用/安装流程、架构与 manifest 选择、blob 摘要检查、tar 层应用、whiteout/opaque 处理、缓存路径和安装元数据。

独立 C pdn 没有 OCI 安装入口。后续评估已有算法的移植、依赖替换和回归，不笼统描述为整个项目需要从零开发 OCI。认证方式、缓存策略和镜像配置执行语义仍需逐项验收，不能由“已有 OCI 模块”推断全部支持。

Dockerfile 构建、push、跨架构和镜像搜索不作为当前首版门槛。

依据：src/pr-cli/src/oci.rs、install.rs、source_parse.rs、install_model.rs。

## 4. 玩家设备本地更新：已有与真正待做

PRoot 当前与 pdn 管理器同一程序构建。更新引擎需组合管理器代码、新版引擎和兼容补丁，再构建完整 pdn 及匹配 loader。不能把纯上游 proot 替换进去后期望 pdn 管理命令继续存在。

### U1. 更新来源与版本发现

**已有：**Termux PRoot vendor 基线和项目版本显示；发布材料有当前项目 commit。

**待做：**玩家检查更新入口、上游 tag/commit 身份、固定源码下载与验证、经过验证的版本/补丁兼容记录。未知版本只能进入实验候选，不承诺自动兼容。

采用源码构建，不依赖“官方是否提供 ARM64 独立二进制”这一前提。

### U2. 可重放源码补丁

**已有：**src/proot 已实际包含本项目兼容改动，docs/proot-improvement.md 已有改动说明。

**待做：**将现有改动整理成有基线、顺序、摘要和测试关联的补丁集；支持在独立候选目录应用到新上游。冲突停止并给出报告，不能在玩家设备上自动猜测解决。上游已合入的修复要核对，避免重复应用。

这里是整理、迁移、维护现有补丁，不是重新实现 SIGSYS、loader 等已有修复。vendor 保持只读。

### U3. 非 Termux Android 本地工具链

**已有：**独立构建脚本可使用宿主 CC/AR 等工具和 NDK sysroot；README 已说明 ARM64 Android 本机构建方式。不是“当前只能 Linux CI 构建”。

**待做：**检查并提供/接入适合玩家宿主的非 Termux ARM64 Android 工具链，包括编译器、链接器、归档器、make、shell/awk、sysroot 和运行库；验证子工具启动和宿主执行能力。普通运行不要求这些工具。

Linux x86_64 NDK 工具不能直接在 ARM64 Android 运行。guest 编译和嵌套 ptrace 是否可用应单独验证，不作为默认可行条件。

### U4. 候选构建

**已有：**scripts/build-proot-nolib.sh 编译完整 pdn、loader 及静态依赖，在临时工作副本构建；构建规则生成匹配 loader 元数据。依赖源码有固定摘要和下载缓存。

**待做：**接收 U1/U2 的候选源码组合；按版本隔离产物、按源码/补丁/工具链/参数标识缓存；提供玩家可用的进度、日志、取消和失败记录。当前依赖缓存以 .done 标记为主，不是完整的参数指纹缓存。

复用已有构建逻辑，不另起一套基础编译器流程；更新不覆盖现有可用产物。

### U5. ELF 检查和修补

**已有：**独立构建检查动态依赖、RPATH/RUNPATH 和宿主路径残留；设置加载相关链接参数。历史 scripts/build.sh 的 fix_tls_alignment 已做 PT_TLS 对齐检查/修补。

**待做：**将已有检查和历史修补逻辑整合成玩家更新步骤，补充架构、interpreter、段布局、适用条件、幂等性和修补后检查。若不希望更新依赖宿主 Python，需替换历史脚本中对应 Python 修补段。

当前独立构建脚本没有直接调用历史 fix_tls_alignment；不能写成当前每次 pdn 构建都执行了 TLS patch。修补需验证真实布局，不能对所有 ELF 无条件只改 p_align。

源码补丁在编译前，ELF 修补在编译后。ELF 修补不能转换 CPU 架构、消除真实库依赖或补齐系统调用兼容逻辑。

### U6. 候选测试

**已有：**独立 pdn 与引擎测试、Rust/guest 集成测试和测试说明。

**待做：**把适合玩家环境的测试组织为候选验证入口，隔离测试目录和测试实例；记录静态检查、轻量运行、完整兼容验证三个层次；针对更新重跑已有兼容回归。设备本地更新不能仅依据编译成功就启用。

现有测试使用 Python、BusyBox fixture，部分还需编译器；玩家验证工具的交付和依赖要明确，而非假设整套开发测试直接可用。

### U7. 启用和回退

**已有：**程序可被手动替换；README 已说明更新前退出旧 Linux 会话、替换文件，已有 rootfs 不需重装。这不是自动版本切换系统。

**待做：**current/previous/candidate 记录，匹配 loader 部署，宿主执行探测、失败回退及版本清理。

首版可采用“退出活动会话再切换”，无需强制实现旧/新引擎并行。若将来支持并行，必须固定每会话引擎和 loader，结束前不删旧资源。APK 内嵌场景仍用预构建产物，不承诺更新器能改写 nativeLibraryDir。

### U8. 构建记录和发布材料

**已有：**package 脚本提供版本、项目 commit、talloc commit、产物 SHA256、源码归档和许可证材料，并检查源码/产物对应关系。

**待做：**扩展玩家构建记录中的上游 commit、补丁摘要、工具链/参数、修补记录及测试结果；与 U7 的版本记录关联。保持原版权和隐私规则。

建议操作阶段为检查、准备、构建、测试、启用、回退；这些是待设计接口，不是现有命令。

## 5. 修订后的实施顺序

| 阶段 | 真正新增/修改的工作 | 应复用的基础 |
| --- | --- | --- |
| A | 独立 pdn 接入现有 Android 封装；结果协议；任务身份与取消 | 路径参数、argv、exec、PTY、ProcessBuilder、JNI |
| B | 安装别名/实例元数据；直接 clone/rename；兼容配置迁移 | install/list/remove、restore、锁、链接迁移、Rust 启动配置 |
| C | 操作事件、FD/流、快照清单；按需后台会话 | 下载回调、取消清理、归档事务、终端 UI |
| D | 可重放补丁、非 Termux 设备工具链、候选构建与 ELF 步骤 | 当前 fork、静态依赖构建、loader、已有 ELF 检查/TLS patch |
| E | 玩家验证入口、停止后切换、版本回退 | 已有测试、版本材料、手动更新路径 |
| F | 按需移植 OCI 等功能 | Rust OCI/元数据/文件操作模块 |

协议形式、Kotlin 封装、项目目录布局、后台会话和 OCI 都是服务于宿主的设计选择。现有 CLI 已可被 App 调用，不要求为了“能嵌入”先完整实现所有建议模块。

## 6. 本次核对与验证

已读取原 MD，核对独立 C 管理器、相关测试、引擎入口、Rust 模块、Android 启动层及构建/发布脚本。

本次使用已有二进制重跑：

| 测试文件 | 结果 |
| --- | --- |
| tests/test_pdn.py | 33/33 通过 |
| tests/test_pdn_config.py | 10/10 通过 |
| tests/test_pdn_archive.py | 13/13 通过 |

共 56 项通过，验证当前执行、配置、删除锁和归档等行为。日志在仓库 build/pdn-audit/。安装和底层其他测试本次仅核对源码，没有宣称重跑；真实 MT 宿主、App 内嵌、新上游更新、本地非 Termux 工具链均未在本次运行验收。

本次仅修订文档，没有修改功能代码。文档核对不等于满足未来新增模块覆盖率；实施新增模块时仍按项目要求保持至少 80% 覆盖率并验证真实 Android 行为。
