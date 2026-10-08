# proot-distro-nolib

**基于 [pr](https://github.com/oonid/pr) 的独立 Android Linux 发行版管理工具，定位类似 Termux 的 proot-distro，但不依赖 Termux 环境及其动态库。**

面向提供终端或命令执行能力的 Android App：把 `pdn` 放进 App 可执行的目录，就能用简单命令安装、登录和管理 Linux。无需先安装 Termux，也不需要宿主额外提供 Bash、Python、curl 或 tar。

当前版本：**v0.6.2 · ARM64 Android · 早期测试版**。

> 我在 **MT 管理器** 里实际用过，目前功能正常。这是我第一次做这类工具，欢迎通过 [Issues](https://github.com/EMERLADD/proot-distro-nolib/issues) 反馈问题、提出建议，一起把它完善起来。
>
> MT 管理器是已验证的宿主环境，不代表所有 Android App 和设备都已测试。

## 这是什么

从文件格式看，`pdn` 是 **ARM64 Android ELF 可执行文件**；从用途看，它是一个 **命令行 Linux 发行版管理器**，把 PRoot 引擎和常用管理功能整合进同一个程序。

- **脱离 Termux**：运行时只动态依赖 Android 系统的 `libc.so`、`libdl.so`；下载、TLS、解压等组件静态链接。
- **简单命令**：`pdn install ubuntu` 安装，`pdn login ubuntu` 登录，名称和命令支持 ASCII 大小写。
- **一个程序管理 Linux**：支持执行命令、挂载目录、保存默认配置、切换 guest 账号、备份恢复和卸载。
- **适合不同宿主 App**：路径来自环境变量或命令参数，不写死 App 包名。终端输入和 App 按钮调用可以使用同一个程序。
- **基于 pr 的 Android 适配**：保留其 PRoot、加载器及 Android 系统调用适配，继续保留原有版权与许可证信息。

`nolib` 表示不依赖 Termux 的动态库，并不是完全不使用任何库。它也不自带图形终端界面；交互终端由宿主 App 提供。仓库保留了 pr 的 Android App/Rust CLI 源码，它们不是本项目独立 `pdn` 的安装前提，也不代表那些组件的功能已经全部移植到 `pdn`。

## 运行条件

- ARM64 / AArch64 Android；构建目标为 Android API 24 及以上，实际可用性还取决于宿主权限与系统限制。
- 宿主允许执行程序及 PRoot 所需的进程跟踪、系统调用，并能访问存放 Linux 的目录。
- Linux rootfs 放在支持 Unix 权限与符号链接的 App 私有目录；`/sdcard` 适合放下载包、备份或共享文件，不适合直接解压 rootfs。
- 宿主需有网络权限；访问共享存储需要相应权限。

“有终端就能使用”是本项目预期的使用方式，不是绕过 Android 权限的保证。对于执行位置受限的 App，开发者可能需要通过 APK 的 `nativeLibraryDir` 部署程序并设置 `PROOT_LOADER`。参见 [Android 加载器说明](docs/targetsdk35-compatibility.md)。PRoot 也不是安全隔离边界，不提供真正的 root 权限。

## 获取与安装

优先从 [Releases](https://github.com/EMERLADD/proot-distro-nolib/releases) 下载独立的 **`pdn`**，或下载包含源码和许可材料的完整发布包。`pdn` 是成品可执行文件，不需要先解压 rootfs 或自行编译。原始文件和完整包的 SHA256 均随版本提供。

也可以获取开发中的构建：打开 [Build pdn 工作流](https://github.com/EMERLADD/proot-distro-nolib/actions/workflows/ci.yml)，选择成功运行记录，在 **Artifacts** 下载 `pdn-android-arm64-提交号`。下载产物通常需要登录 GitHub；自动构建产物有保留期限，不等同于长期 Release。

解压下载的 artifact，再解压其中的 `proot-distro-nolib-v0.6.2-android-arm64.tar.gz`。里面同时提供：

- `pdn`：建议使用的命令名。
- `proot-distro-nolib`：与 `pdn` 内容相同，任选一个即可，不必两个都放进 bin。
- `proot-loader`：有外部加载器需求的宿主可使用；普通场景先使用内嵌加载器。
- `jniLibs/arm64-v8a/libpdn.so`、`libproot-loader.so`：供 APK 打包使用，分别与 `pdn`、`proot-loader` 内容相同。

将 `pdn` 放进宿主允许执行的 bin 目录，在该目录执行：

```sh
chmod 755 pdn
./pdn version
```

如果 bin 已经在 `PATH` 中，就可以直接输入 `pdn`。共享存储可能禁止执行文件，单纯 `chmod` 不能改变这个限制。

### 以 MT 管理器为例

下载 Release 中的 `pdn`，用 MT 管理器复制到：

```text
/data/user/0/bin.mt.plus/files/term/bin/pdn
```

这是 MT 管理器自己的终端 bin 目录。假设文件下载在 `/sdcard/Download/pdn`，也可以在 **MT 管理器的终端**执行：

```sh
cp /sdcard/Download/pdn /data/user/0/bin.mt.plus/files/term/bin/pdn
chmod 755 /data/user/0/bin.mt.plus/files/term/bin/pdn
pdn version
pdn install alpine
pdn login alpine
```

如果下载位置不同，请修改 `cp` 的源路径。更新时退出旧 Linux 会话，再替换程序并重新赋予执行权限；已有 Linux 不需要重装。若提示找不到 `pdn`，先用完整路径运行 `/data/user/0/bin.mt.plus/files/term/bin/pdn version`，检查终端 PATH。

该路径只作为 MT 管理器示例，没有硬编码进 `pdn`；其他 App 应使用自己的可执行目录。原始 `pdn` 是便捷下载项，转发发布时请同时保留完整包中的对应源码与许可证。

## ARM64 下载源

四个发行版均内置 `official` 源。Alpine、Ubuntu、Arch 默认先尝试国内镜像，
失败后回退官方；Debian 只保留一个 `official` 源，直接下载官方 Docker rootfs 构建。
GitHub 仓库的 `/raw/` 地址会重定向到同一文件，不作为第二个备用源。

| 发行版 | 官方来源 |
| --- | --- |
| Alpine | [Alpine CDN](https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/) |
| Ubuntu | [Ubuntu Base](https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/) |
| Debian | [debuerreotype 官方 Docker 构建](https://github.com/debuerreotype/docker-debian-artifacts) |
| Arch | [Arch Linux ARM 官方镜像](https://fl.us.mirror.archlinuxarm.org/os/multi/) |

```sh
pdn mirrors alpine
pdn install alpine --mirror official
```

指定 `--mirror official` 后只使用该源。所有来源下载同一个固定 ARM64 版本，
校验预设文件大小和 SHA256，通过后才解压；不会改用未经校验的 `latest` 包。
Kotlin 接入时调用 `pdn.install("alpine", mirror = "official")`。

## 嵌入 Android App

Java/Kotlin 可以通过 `PdnRuntime` 生成安装、执行等操作，再用 `PdnOperations`
接收阶段、进度、错误建议及最终结果。GUI 无需解析 CLI 输出；Linux 的 stdout/stderr
保持独立。见 [Java/Kotlin 事件 API 与 AAR 结构](docs/pdn-events.md)。

**AAR 已实测可用**：全新的独立 Android App 仅导入生成的 AAR 与 Kotlin 标准库，
已进入 Alpine，并在交互终端成功执行 `apk add nano`。独立工程和构建方式见
[AAR 验证 App](examples/aar-probe/README.md)。

每个 Release 同时提供 `pdn-engine-版本号.aar`、`libpdn.so`、`libproot-loader.so` 和原始 ELF `pdn`、`proot-loader`。AAR 是非插桩 Debug 引擎构建，包含封装 API 和 ARM64 原生程序；宿主需提供 Kotlin 标准库。也可以单独将两个 `.so` 放入 App 的 `jniLibs/arm64-v8a/`，由 Android 解压到 `nativeLibraryDir` 后通过进程调用。它们是原生可执行程序，不是 `System.loadLibrary` 加载的 PDN JNI API。

`PdnRuntime` 提供 `install("alpine")`、`login("alpine")`、`exec("alpine", listOf("/bin/echo", "hello"))` 和 `remove("alpine")`，返回 `ProcessBuilder`，调用 `.start()` 启动。

示例 App 提供 Alpine 软件安装面板：输入包名或点选常用软件，点击安装即可；自动更新索引，并可查看已安装软件和执行日志。界面通过 Kotlin `exec()` 接口调用 Linux 内的 `apk`。

实测：通过 GUI 在 Alpine 中安装 `curl`，随后在 Alpine 中执行 `curl -v https://example.com/` 可正常访问。图形界面安装、Linux 程序运行和 HTTPS 访问已跑通。

目录安排、Gradle 打包配置、Kotlin API、安装/执行命令和 PTY 接入示例见 [Android App 接入教程](docs/android-embedding.md)。正式转发这些文件时同时提供完整发布包中的对应源码和许可材料。

## 快速开始

默认 Linux 存储目录是 `$HOME/.local/share/pdn/rootfs`。也可以先指定宿主可访问的私有目录：

```sh
export PDN_ROOTFS_DIR="$HOME/linux"
pdn list --available
pdn install alpine
pdn login alpine
```

也支持：

```sh
pdn install Ubuntu
pdn install debian
pdn install arch
pdn ls
pdn login ubuntu
```

退出 Linux 后执行管理操作。已有 rootfs 可以放到 `$PDN_ROOTFS_DIR/名字/`，或者使用 `pdn login --rootfs /完整/rootfs/路径`，不需要重新安装。

### 支持的发行版与下载

| 名称 | 固定版本 | 架构 |
| --- | --- | --- |
| Alpine | 3.24.2 | ARM64 |
| Ubuntu Base | 24.04.5 LTS | ARM64 |
| Debian slim | 13 trixie，20261005 | ARM64 |
| Arch Linux ARM | 2026.08 | ARM64 |

下载会检查固定大小和 SHA256。部分发行版有国内外多个源，失败时按顺序切换；目前还没有延迟测速排名和断点续传。Debian 的两个入口属于同一个上游 GitHub 资源，不是两个独立镜像站。Arch 压缩包约 791 MiB，请预留数 GiB 空间。

```sh
pdn mirrors ubuntu
pdn install ubuntu --mirror tuna
pdn install alpine --archive /路径/对应固定版本的rootfs.tar.gz
```

`install --archive` 仍要求匹配内置版本的校验值；它不是任意归档导入。来源和校验值见 [rootfs 来源目录](docs/pdn-rootfs-sources.md)。

### 执行、挂载与默认配置

```sh
pdn exec ubuntu -- /usr/bin/id
pdn exec ubuntu -- /bin/sh -c 'echo hello; uname -r'
pdn login ubuntu --bind /sdcard:/mnt/shared
pdn config ubuntu --bind /sdcard:/mnt/shared --work-dir /root --env LANG=C.UTF-8
pdn login ubuntu
```

`--bind` / `-b` 可以重复指定；guest 修改挂载文件会直接修改宿主文件。临时挂载只对当前会话有效，`config` 保存的挂载会在后续登录与执行时自动加载。

```sh
pdn config ubuntu --show
pdn login ubuntu --no-config
pdn config ubuntu --clear
pdn login ubuntu --user root --work-dir /tmp --env EXAMPLE='two words'
```

`config` 每次保存会替换整套默认配置。命令行上的账号、工作目录与环境变量覆盖默认值，挂载则追加；`--user` 使用 guest 中已有的账号名或数字 UID，可带数字 GID。默认是模拟 root，不会创建账号。

### 备份与恢复

先退出该 Linux 的会话：

```sh
pdn backup ubuntu /sdcard/ubuntu.tar.gz
pdn restore ubuntu-copy /sdcard/ubuntu.tar.gz
pdn login ubuntu-copy
```

备份文件必须在源 rootfs 之外，恢复必须使用新名字；两者都不会覆盖已有目标。备份保留 Linux 文件数据和 PRoot 内部链接的迁移关系，排除宿主启动配置、临时 loader、运行时目录内容及特殊节点。换 App 后请重新设置挂载目录。

目录会补齐 owner rwx 权限，不恢复宿主所有者和 setuid/setgid；这是一份适合无 root 环境迁移的 rootfs 备份。详细边界、归档限制和中断处理见 [完整手册](docs/proot-distro-nolib.md)。

### 指令索引

| 指令 | 用途 |
| --- | --- |
| `install` | 安装内置发行版 |
| `mirrors` | 查看 rootfs 下载源 |
| `list` / `ls` | 查看已安装系统；`--available` 查看可安装版本 |
| `login` | 交互登录，也支持 `-- COMMAND` |
| `exec` | 执行指定 guest 命令 |
| `config` | 保存、查看、清除默认启动参数 |
| `backup` / `restore` | 备份与恢复到新名字 |
| `uninstall` / `remove` | 确认后删除系统及其中全部数据；`--yes` 跳过确认 |
| `version` / `help` | 查看版本、帮助 |
| `proot` | 直接使用底层 PRoot 参数 |

## 本地构建

独立 `pdn` 构建不需要 Java、Gradle 或 Rust。需要 Git、Make、Clang/LLVM 工具、curl、tar、xz/bzip2、pkg-config 和 Android NDK。第一次构建需联网下载带固定校验值的依赖源码。

```sh
git clone https://github.com/EMERLADD/proot-distro-nolib.git
cd proot-distro-nolib
git submodule update --init --depth 1 vendor/samba
make NDK_PATH=/你的/Android/NDK/目录
```

在 Linux x86_64 主机上，使用 NDK 自带的 LLVM 工具，例如：

```sh
export NDK_PATH="$HOME/Android/Sdk/ndk/26.3.11579264"
export PATH="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
make NDK_PATH="$NDK_PATH"
```

在 ARM64 Android 本机编译时，需要能在 Android 上运行的 Clang/LLVM 工具，再使用 NDK 的 sysroot；不能直接运行上面的 Linux x86_64 编译器。构建工具可以来自 Termux，但产物运行不依赖 Termux。

输出位于 `build/proot-distro-nolib/arm64/`。构建会检查 ELF 动态依赖、RPATH 和残留的宿主路径；不安装 App，也不改写宿主 bin。

```sh
make help
make test
make package
make clean
```

`make test` 必须在允许 PRoot 运行的 ARM64 Android 环境执行，还需要 Python 3 和仓库测试使用的 BusyBox fixture。v0.6.2 的回归与覆盖率记录见 [更新记录](CHANGELOG.md)；Ubuntu/Debian/Arch/Alpine 的核心链路已验证，不代表所有设备兼容性。

`make package` 先编译，再打包已提交的源码和产物。打包前需提交项目文件，确保源码对应当前提交；输出在 `build/packages/`。更换编译器、NDK 或依赖编译参数时先 `make clean`，避免复用旧静态库。

## GitHub 自动构建

工作流文件：[`.github/workflows/ci.yml`](.github/workflows/ci.yml)。推送到 `main`、推送 `v*` 标签、Pull Request 或手动 **Run workflow** 都会触发。

流程使用 Ubuntu 24.04 与固定 NDK `26.3.11579264`：

1. 获取仓库及构建所需的 talloc 子模块源码。
2. 从固定来源下载、校验并静态编译依赖。
3. 编译 ARM64 `pdn` 与 loader，检查动态依赖及宿主路径。
4. 打包二进制、许可证、使用材料和对应源码，生成 SHA256。
5. 单独构建引擎 AAR，逐字节核对其中的 PDN 和 loader 与原始 ELF，更新附件 SHA256。
6. 上传 Actions artifact，保留 30 天。
7. `main` 推送包含尚未发布的版本号时，自动创建对应 `v版本号` 标签和预发布 Release；同版本已发布时跳过，下一次发布先递增补丁版本号。`v*` 标签推送也可以发布，但标签必须与代码版本一致。

Linux runner 执行交叉编译与产物检查，**不会被当成 Android 运行测试通过**。Pull Request 和手动构建只生成附件；发布步骤仅在 `main` 或版本标签推送后执行。

仅构建引擎时，在 `android/` 执行 `sh gradlew -PpdnEngineOnly=true :proot-engine:bundleDebugAar`，无需配置 GUI 和终端库模块；先按上文构建原生 PDN 与 loader。

原 pr App 的构建保留在 [Legacy pr Android App](.github/workflows/legacy-pr.yml)，仅手动触发；它不属于独立 `pdn` 的默认构建流程。

## 发布材料与源码

发布包包含：

| 文件 | 内容 |
| --- | --- |
| `pdn`、`proot-distro-nolib`、`proot-loader` | ARM64 可执行程序与可选 loader |
| `jniLibs/arm64-v8a/` | APK 使用的 `libpdn.so` 与匹配的 `libproot-loader.so` |
| `SHA256SUMS` | 包内程序及两个 APK 原生库文件名产物的校验值 |
| `BUILD-INFO.txt` | 项目版本、源码提交号、talloc 来源提交号 |
| `README.md`、`CHANGELOG.md`、`docs/` | 项目介绍、更新记录和详细使用说明 |
| `LICENSE`、`licenses/` | 项目许可证映射与第三方许可文本 |
| `source.tar.gz` | 对应仓库源码、构建所需的 talloc 源码，以及四个依赖的原始源码归档 |

发布目录还提供独立的 `pdn-engine-版本号.aar`、`pdn`、`proot-loader`、`libpdn.so`、`libproot-loader.so`；外部 `SHA256SUMS` 同时校验 AAR、原始 ELF、两个 `.so` 和完整 `.tar.gz`。可在支持该工具的环境执行 `sha256sum -c SHA256SUMS`。源码包可独立解压后用上述 NDK 工具链运行 `make`；四个依赖归档已包含，编译时仍会验证其校验值。它不包含 Android SDK/NDK 本身。

转发二进制时请一起保留这些材料、对应源码与第三方许可证，不要只留下一个改名后的程序。项目继承上游的许可证，不把所有组件统一宣称为 MIT；具体范围见 [LICENSE](LICENSE) 和源文件中的声明。

## 来源与致谢

- [oonid/pr](https://github.com/oonid/pr)：本项目基底，提供 Android PRoot 适配及原有 App/CLI 实现。
- [PRoot](https://github.com/proot-me/proot)、[Termux PRoot](https://github.com/termux/proot)：PRoot 引擎及 Android 相关改动；本仓库引擎源代码沿用 GPL-2.0-or-later 声明。
- [Termux proot-distro](https://github.com/termux/proot-distro)：使用方式与发行版管理思路的参考；独立 `pdn` 不要求安装它。
- [talloc / Samba](https://www.samba.org/)、[curl](https://curl.se/)、[Mbed TLS](https://github.com/Mbed-TLS/mbedtls)、[libarchive](https://www.libarchive.org/)、[zlib](https://zlib.net/)：构建中使用的组件。talloc 源文件声明 LGPL-3.0-or-later；其余组件许可随发布包附带。

`nolib` 指运行时脱离 Termux，不抹去代码来源或上游贡献。原作者版权、许可证和 `pdn version` 中的基底信息继续保留。

## 反馈

欢迎提交 [Issue](https://github.com/EMERLADD/proot-distro-nolib/issues)，描述宿主 App、Android 版本、`pdn version`、复现命令和错误输出即可。不要贴密码、Token、设备序列号或其他隐私信息。

想法、建议、文档改进也欢迎。当前暂不支持 OCI/Docker 镜像、跨架构模拟、自动镜像测速或后台会话管理，后续按实际需求逐步完善。
