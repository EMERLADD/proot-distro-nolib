# PDN 原生 CLI 手册

简体中文 | [English](proot-distro-nolib.md) · [返回 README](../README.md)

PDN 将发行版管理与 PRoot 引擎集成到同一个 ARM64 Android ELF 中，基于 PRoot 5.4.0-pr。运行时不要求宿主提供 Python、Bash、BusyBox、curl 或 tar；依赖 Android 系统的 libc/libdl。宿主目录和 loader 路径由调用方提供，不推导固定 App 包名。

`pdn` 和 `proot-distro-nolib` 是相同字节的程序，入口名称决定默认帮助与兼容行为。使用管理命令时推荐 `pdn`；底层引擎参数通过 `pdn proot` 传入。

## 目录

- [命令索引](#命令索引)
- [目录与初始化](#目录与初始化)
- [安装与下载来源](#安装与下载来源)
- [实例名称与元数据](#实例名称与元数据)
- [登录和执行命令](#登录和执行命令)
- [挂载、环境与用户](#挂载环境与用户)
- [保存默认配置](#保存默认配置)
- [复制与重命名](#复制与重命名)
- [备份与恢复](#备份与恢复)
- [删除实例](#删除实例)
- [退出码与事件](#退出码与事件)
- [底层引擎与兼容功能](#底层引擎与兼容功能)
- [构建与产物](#构建与产物)

## 命令索引

| 命令 | 用途 |
| --- | --- |
| `pdn install DISTRO [--name INSTANCE] [--mirror NAME \| --archive PATH]` | 安装固定来源的发行版，可指定实例名称 |
| `pdn mirrors [DISTRO] [--json]` | 查询下载来源及固定顺序 |
| `pdn list --available [--json]` | 查询内置发行版 |
| `pdn list [--json]` / `pdn ls` | 查询本地实例与元数据 |
| `pdn login NAME\|--rootfs PATH [OPTIONS] [-- COMMAND ARG...]` | 交互登录或执行明确命令 |
| `pdn exec NAME\|--rootfs PATH [OPTIONS] -- COMMAND ARG...` | 执行明确命令，必须有 `--` |
| `pdn config NAME\|--rootfs PATH [OPTIONS]` | 查询、替换或清除默认配置 |
| `pdn clone SOURCE TARGET` | 复制为独立实例 |
| `pdn rename SOURCE TARGET` | 重命名实例并迁移自身路径 |
| `pdn backup NAME FILE.tar.gz` | 备份到新归档 |
| `pdn restore NAME FILE.tar.gz` | 离线恢复为新实例 |
| `pdn uninstall NAME [--yes\|-y]` / `pdn remove` | 删除整个实例 |
| `pdn version` / `pdn --version` | 查看 PDN 及基础引擎版本（也支持 `-V`、`--about`） |
| `pdn help` / `pdn --help` | 查看管理帮助 |
| `pdn proot [PROOT OPTIONS...]` | 调用底层引擎 |

命令、选项与实例名称的匹配忽略 ASCII 大小写；路径、用户名称、环境键值和 guest 命令参数保留大小写。`--` 后的内容按参数数组传递，不会自动解释为 shell 表达式。

## 目录与初始化

```sh
export PDN_ROOTFS_DIR=/data/local/tmp/pdn/linux
export PROOT_TMP_DIR=/data/local/tmp/pdn/tmp
mkdir -p "$PROOT_TMP_DIR"
pdn install alpine
pdn login alpine
```

这个示例适用于有权写入 `/data/local/tmp` 的 Android shell。普通 App 应使用自己的私有目录，不能凭这个路径示例获得 shell 权限。rootfs 所在文件系统须支持 Unix 权限与符号链接，不能直接解压到 `/sdcard`。

| 环境变量 | 作用 |
| --- | --- |
| `PDN_ROOTFS_DIR` | 实例的父目录；未设置时为 `$HOME/.local/share/pdn/rootfs` |
| `PROOT_TMP_DIR` | loader 临时目录，优先于 `TMPDIR` |
| `TMPDIR` | 未提供非空 `PROOT_TMP_DIR` 时使用的临时目录 |
| `PROOT_LOADER` | 可选的外部 loader 路径；App 接入通常由宿主指定 |
| `PDN_CA_BUNDLE` | 可选的 PEM 证书信任文件 |

当前工作目录不会自动成为 rootfs 目录。Android shell 的 `HOME` 可能为 `/`，此时默认目录可能不可写，应显式指定 `PDN_ROOTFS_DIR`。安装会创建父目录，不要预先创建目标 `linux/alpine`，已有目标会被拒绝。

显式提供的临时目录必须事先存在，并有写入和目录搜索权限；登录不会替你创建这些显式路径。两种临时目录变量都未设置时，管理器在所选 rootfs 内创建权限为 0700 的 `.pdn-tmp`。目录错误报告所选路径、来源、系统错误、errno 和相应建议，不会静默换到其他目录。

## 安装与下载来源

```sh
pdn list --available --json
pdn mirrors alpine
pdn install alpine
pdn install ubuntu --mirror official
pdn install alpine --archive /sdcard/alpine-minirootfs-3.24.2-aarch64.tar.gz
```

| 发行版 | 固定起点 | 压缩大小 | 回退顺序 |
| --- | --- | --- | --- |
| Alpine | 3.24.2 | 3.8 MiB | tuna、ustc、nju、official、dotsrc |
| Ubuntu Base | 24.04.5 LTS，noble | 28.5 MiB | tuna、ustc、official |
| Debian slim | 13 trixie，20261005 | 28.8 MiB | official |
| Arch Linux ARM | 2026.08 | 790.9 MiB | tuna、ustc、nju、official |

准确字节数、SHA256 和上游身份见[中文 rootfs 来源](pdn-rootfs-sources.zh-CN.md)。Arch 解压后约 2 GiB，应为下载、rootfs 和更新预留数 GiB 空间。

未指定来源时，网络、HTTP、大小或 SHA256 错误会使程序丢弃本次下载，再从下一个来源重新下载。指定 `--mirror` 时只试该来源；取消操作不会触发回退。没有自动测速或断点续传。

连接超时为 8 秒，连续 30 秒低于 1 KiB/s 会停止传输；总传输上限为 Alpine 90 秒、Ubuntu/Debian 10 分钟、Arch 30 分钟。`--archive` 先复制到暂存目录再核验，必须匹配固定归档，不能与 `--mirror` 同时使用。

安装配置 DNS 为 223.5.5.5 与 1.1.1.1。Alpine/Ubuntu 的软件仓库跟随成功镜像，离线安装采用第一个来源的设置；Debian 使用官方与 security 仓库，并去掉容器专用 `docker-clean` 钩子。Ubuntu/Debian 缺少 CA bundle 时从 Android 证书或 `PDN_CA_BUNDLE` 初始化，并为 PRoot 配置 apt。包签名校验保持启用。

Arch 设置 pacman 镜像顺序，关闭 PRoot 内不可用的 pacman 文件系统/syscall 沙箱机制，并在暂存 rootfs 内执行密钥初始化；无需宿主 Bash 或 GnuPG。路径过长时可指定较短的私有 `PROOT_TMP_DIR`。系统升级用 `pacman -Syu`，固定归档只是滚动发行版的安装起点。

安装先写隐藏暂存目录，配置完成后以不覆盖方式发布。已有文件、目录、软链接和大小写变体都不会被替换。普通失败、Ctrl-C 和 SIGTERM 清理暂存目录；SIGKILL 或断电可能留下隐藏目录，确认没有操作运行后再清理。

解压拒绝绝对路径、目录逃逸、通过软链接写入以及设备/FIFO 条目。硬链接转换为相对软链接别名；保留普通权限和 sticky bit，确保目录所有者 rwx，去掉 setuid/setgid，不恢复宿主所有权。解压数据上限为 Alpine 512 MiB、Ubuntu/Debian 1 GiB、Arch 4 GiB，并限制条目与单文件大小。

下载启用 HTTPS 证书与主机名校验，默认使用 Android `/system/etc/security/cacerts`；代理环境按 libcurl 规则处理。VPN 或 Android 系统代理不等于自动提供 Linux 内的代理环境，参见[常见问题](pdn-faq.md)。

## 实例名称与元数据

```sh
pdn install alpine --name ai-python
pdn install alpine --name ai-tools
pdn list --json
```

新名称最多 128 个 ASCII 字符，首字符须为字母、数字或下划线，其后还允许点和短横线；不能使用路径或以点开头。目标名称冲突不区分大小写。旧实例查找兼容原有名称规则；如果多个目录只在大小写上不同，应使用 `--rootfs` 明确选择。

每个直接子目录是一份 rootfs，不需要注册或复制。登录也支持指向现有 rootfs 的软链接，管理迁移与删除则要求真实目录。`list` 只查询本地目录，不下载或验证整份 Linux。

新实例保存稳定 ID、名称、发行版、版本、架构、来源、摘要和创建时间。旧 rootfs 的 JSON 查询可返回 `instance: null`，查询不会自动改写它。备份恢复与 clone 创建新身份，rename 保留身份。

## 登录和执行命令

```sh
pdn login ubuntu
pdn login --rootfs '/私有目录/another rootfs'
pdn login ubuntu -- /usr/bin/id
pdn exec alpine -- /bin/sh -c 'apk update && apk add curl'
pdn exec alpine -- /bin/sh -c 'printf "%s\n" "two words"; id'
```

交互登录优先选择可执行的 guest `/bin/bash`，否则使用 `/bin/sh`；通常进入 HOME，失败时回退 `/`。明确命令默认从 `/` 开始，经 guest `/bin/sh` 和 `exec "$@"` 保留参数边界。`exec` 必须提供 `-- COMMAND ARG...`，不会退回交互 shell。需要管道、重定向或 `&&` 时明确使用 `/bin/sh -c`。

默认启用 fake root、link2symlink、`-L` 兼容，绑定 `/dev`、`/proc`、`/sys`，模拟内核版本 `6.17.0-pr`。fake root 不会获得 Android 真 root。标准输入输出和 guest 状态保留；主命令结束时清理后台 guest 子进程。

guest 必须有自己的 shell、库和常规目录。登录既有 rootfs 不会自动改写 DNS、账户或包管理配置。guest 的 HOME/USER/LOGNAME/PATH/SHELL/TMPDIR 由启动器设置，宿主 LD_PRELOAD、LD_LIBRARY_PATH、ENV、BASH_ENV 被移除；其他变量可继承，这不是安全隔离边界。

## 挂载、环境与用户

```sh
pdn login ubuntu --bind /sdcard:/mnt/shared
pdn exec ubuntu -b '/sdcard/My Files:/mnt/shared' -- /bin/ls /mnt/shared
pdn exec ubuntu --env EXAMPLE='two words' --work-dir /tmp -- /usr/bin/env
pdn login ubuntu --user root
```

| 选项 | 含义 |
| --- | --- |
| `--bind` / `-b HOST[:GUEST]` | 可重复；宿主须为可访问的已有文件或目录 |
| `--env` / `-e KEY=VALUE` | 可重复；保留原值，同一键最后一次赋值生效 |
| `--user` / `-u USER[:GID]` | guest 用户名或数字 UID，可附数字 GID |
| `--work-dir` / `-w /PATH` | guest 内的绝对起始目录 |
| `--no-config` | login/exec 忽略保存的配置 |

相对宿主路径按调用者的当前目录解析并规范化。guest 目标须为绝对路径，省略时使用规范化宿主路径；包含空格的参数要加引号。前端不支持路径内冒号及底层 bind 的 `!` 高级后缀。自定义 bind 在默认绑定之后生效，相同目标以后者优先。

挂载受宿主权限约束，guest 写入会修改原宿主文件，不能因此获得 Android 存储权限。临时调用选项不会自动保存。

默认身份为 root。明确用户名须存在于 guest 普通 `/etc/passwd`，该文件和 `etc` 目录不能是软链接。数字 UID 找不到账户时，HOME 为 `/`、SHELL 为 `/bin/sh`、USER/LOGNAME 为数字 ID，默认 GID 等于 UID。明确 GID 可覆盖它。不会创建账户或添加补充组；guest 的虚拟补充组与真实 Android 组分离，宿主访问权限不变。

环境值在 guest shell 启动后导出，不会重配置宿主 tracer；修改 SHELL 变量不会改变已经选定的账户登录 shell。工作目录在 bind 应用后于 guest 内解析，进入失败时不执行目标命令。

## 保存默认配置

```sh
pdn config ubuntu --bind /sdcard:/mnt/shared --work-dir /root --env LANG=C.UTF-8
pdn config ubuntu --show
pdn login ubuntu
pdn login ubuntu --no-config
pdn config ubuntu --clear
```

`config` 传入启动选项时替换整份配置，不是增量追加；没有选项或使用 `--show` 时打印 JSON，`--clear` 删除配置，也支持 `--rootfs PATH`。

配置以受限、不可执行的数据保存到 rootfs 的 `.pdn-config`，权限为 0600，并原子替换。写入或清除前退出相关会话。损坏配置会拒绝启动，可用 `--no-config` 进入恢复会话后重配。`--show` 会显示保存的环境值。

临时指定的用户、目录和环境键覆盖保存值，bind 则追加并按后者优先。保存的宿主 bind 来源不存在时会报错，不会自动忽略。guest 命令本身不能保存。

## 复制与重命名

```sh
pdn clone ai-python ai-python-test
pdn rename ai-python-test workspace-python
pdn login workspace-python
```

clone 复制为独立 rootfs，生成新 ID 和时间，标记 `source=clone`，保留已知发行版来源、版本及摘要；未知旧来源可为 null。采用备份的复制规则，需要暂存归档和完整目标 rootfs 的空间。自身宿主绝对软链接转换为 guest 路径，内部 `.l2s` backing 重定位到目标，外部 backing 拒绝复制。

rename 直接移动目录，保留 ID、时间和来源；旧 rootfs 可继续没有元数据。它迁移自身宿主绝对链接、`.l2s` backing 与保存的内部 bind，识别 Android `/data/user/0` 与 `/data/data` 等目录别名。外部项目 bind 保持共享，普通文件内容与环境字符串中的路径不自动改写。

目标冲突拒绝覆盖；完全相同的实际名称拒绝操作，允许只改变大小写的 rename。两种操作持有全局安装锁和来源排他锁，活动实例返回 `operation_busy`，先退出会话。

普通失败及 SIGINT/SIGTERM 在发布前回滚；rename 恢复链接和配置、元数据的原始字节与权限。回滚失败报告 `rollback_failed` 并保留恢复快照，按建议检查或恢复备份。SIGKILL、断电后的持久恢复尚未实现。

## 备份与恢复

```sh
pdn backup ubuntu /sdcard/ubuntu.tar.gz
pdn restore ubuntu-copy /sdcard/ubuntu.tar.gz
pdn login ubuntu-copy
```

备份要求真实实例目录且没有活动会话，持有来源排他锁与安装锁。输出须为来源 rootfs 外的新文件名，不覆盖已有文件或软链接；先写私有临时归档，成功后发布。PDN 外的宿主修改不受这些建议锁约束，备份时保持来源静止。

保留普通文件、常规权限、修改时间与软链接，迁移内部 PRoot 硬链接模拟关系。原生硬链接复制为独立文件，不保留共享 inode；PRoot 模拟链接可保留共享数据关系。不会通过软链接或 bind 把外部文件复制进归档。

排除宿主相关 `.pdn-config*`、`.pdn-tmp`、`/dev`、`/proc`、`/sys` 的内容及 socket/FIFO/设备节点。恢复不启用归档里的宿主配置，应重新 `pdn config`。不恢复宿主所有权，去掉 setuid/setgid，确保目录所有者 rwx。

restore 接受 rootfs 直接位于顶层的普通或 gzip tar，离线执行，不运行安装脚本或核验内置目录摘要。只能发布到配置的 rootfs 父目录，并要求新名称。上限为 100 万条目、单个普通文件 8 GiB、声明文件总数据 64 GiB；备份同限，深度最多 256。

解压保护目录逃逸与通过软链接写入，归档硬链接转换为相对别名。普通失败及可处理取消清理部分输出；强制终止可能留下 `.pdn-backup-*`、`.pdn-restore-*`。发布要求宿主支持不覆盖 rename，失败时不退回覆盖目标。

## 删除实例

```sh
pdn uninstall alpine
pdn remove ubuntu --yes
```

删除 rootfs 的所有软件和文件，没有撤销。交互提示显示实际目录，只有 `y` 或 `yes` 确认，其他输入或 EOF 取消；调用方已经决定删除时用 `--yes` / `-y` 跳过确认。成功退出 0，取消退出 1，参数、锁与文件操作失败退出 2。

只能删除父目录的直接真实子目录，不支持任意 `--rootfs` 删除。拒绝来源软链接、大小写歧义、非目录、路径逃逸和以 `/` 为父目录。内部软链接只删除链接，不跟随目标；拒绝跨文件系统遍历。权限错误或中断可能留下部分删除的目录，修正问题后再次删除。

已有会话不会被强制终止。旧 PDN、原始 `proot` 和无关宿主程序可能不参与锁，应先自行停止；锁不是防恶意宿主修改的隔离机制。

## 退出码与事件

管理错误通常退出 2；guest 命令使用实际进程状态，应同时检查启动错误与 guest 结果。原始 CLI 保留 stdout/stderr 文本；App 可通过 Java/Kotlin 的操作接口接收独立标准流、JSONL 事件、最终结果、分类和建议。

完整字段与结果定义见[事件接口](pdn-events.md)，异步任务与终端见[AAR API](pdn-aar-api.md)。不应从终端文本猜测结构化错误，也不应把 fake root 或代理环境继承视为 Android 提权。

## 底层引擎与兼容功能

```sh
pdn proot --help
unset PROOT_NO_SECCOMP
pdn login alpine
```

允许 seccomp 自动选择时，移除 `PROOT_NO_SECCOMP`；禁用时设置 `PROOT_NO_SECCOMP=1`。任何已设置的值，包括 `0` 或空字符串，都禁用自身加速过滤器。tracer 没有继承过滤器、查询成功且未显式禁用时才尝试启用；已有过滤器、查询或安装失败都保留完整 syscall 追踪。版本里的 `seccomp_filter = yes` 仅表示编译支持，不表示当前启用。

AAR 默认设置禁用变量，普通 App 通常已继承 Android 过滤器。不能靠删除环境变量绕过系统过滤。详细性能与兼容验证见[测试记录](pdn-error-testing.md)。

底层引擎自己的临时目录顺序为非空 `PROOT_TMP_DIR`、非空 `TMPDIR`、`/tmp`；这个默认行为与前端 login 创建 `.pdn-tmp` 不同。底层路径也须已经存在且可写。

Android 继承过滤器阻断 `openat2`、`faccessat2` 或 `renameat2` 时返回 `ENOSYS`，让调用方选择自身回退，不丢弃结构参数、访问标志或 rename 约束。没有回退的软件会失败；未被过滤器阻断的调用保留原有路径。SIGSYS 诊断通过引擎 stderr 的 verbosity 1 及以上输出，例如 `-v 1` 或 `PROOT_VERBOSE=1`。

可选 guest `/dev/full` 兼容由宿主设置：

```sh
PROOT_EMULATE_DEV_FULL=1 pdn login alpine
PROOT_EMULATE_DEV_FULL=1 pdn exec alpine -- /bin/sh -c 'printf x > /dev/full'
```

默认关闭；AAR 可设置返回的 ProcessBuilder 环境。启用后提供读零、写入 ENOSPC 的 guest 兼容行为，不创建 Android 内核设备；显式 bind 的其他文件保持原行为。会增加读写 syscall 追踪开销，仅覆盖常用同步、原生 64 位 guest I/O；读取可能最多返回 64 KiB 的短读，不支持 mmap，`preadv2` / `pwritev2` 返回 `EOPNOTSUPP`。

## 构建与产物

```sh
make
make test
make help
make NDK_PATH=/路径/android-ndk CC=clang
```

默认交叉编译 ARM64、Android API 24 及以上。需要构建宿主的 Clang、LLVM 工具、Make、POSIX shell、curl、tar 与 Android NDK sysroot；测试需要 Python 3 和允许 PRoot 追踪的 ARM64 Android 环境。ARM64 Android 不能直接运行 NDK 内的 Linux x86_64 编译器，应使用原生 Clang 配合 NDK sysroot。

NDK 按 `ANDROID_SDK_ROOT/ndk`、`ANDROID_HOME/ndk`、`$HOME/android-sdk/ndk` 查找，也可用 `ANDROID_NDK_HOME` 或 `NDK_PATH` 指定。构建复制工作源码到临时目录，不修改 vendor。`make clean` 只清除 `build/proot-distro-nolib`，同时清除该目录的依赖缓存。

首次构建下载固定摘要的 curl、Mbed TLS、libarchive 与 zlib 源码并静态链接，之后复用缓存。运行时不要求宿主提供这些程序或 TLS 共享库。

| 产物 | 用途 |
| --- | --- |
| `pdn` / `proot-distro-nolib` | 同字节的独立 ELF，使用 `/system/bin/linker64`，动态依赖仅 libc/libdl |
| `proot-loader` | 可选外部 ARM64 loader |
| `proot-distro-nolib.debug` | 调试用未剥离 ELF |
| `jniLibs/arm64-v8a/libpdn.so`、`libproot-loader.so` | 用于 APK 解压和执行的 ELF 文件名，并非 JNI 共享库 |
| `SHA256SUMS`、`dynamic.txt`、`licenses/` | 校验、动态依赖检查及许可证 |

默认输出在 `build/proot-distro-nolib/arm64/`。构建检查拒绝 RPATH/RUNPATH、额外动态依赖及残留宿主私有路径。转发时保留对应源码与许可证。AAR 中 PTY JNI 才是实际 JNI 共享库，App 接入和发布步骤见[构建与发布](pdn-build-and-release.md)。历史测试与覆盖率集中在[测试记录](pdn-error-testing.md)。
