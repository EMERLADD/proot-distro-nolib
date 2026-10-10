# 发行版管理

简体中文 | [English](pdn-distributions.en.md) · [返回 README](../README.md)

## 目录

- [目录与已有 rootfs](#目录与已有-rootfs)
- [安装与下载源](#安装与下载源)
- [执行、挂载与默认配置](#执行挂载与默认配置)
- [备份与恢复](#备份与恢复)
- [指令索引](#指令索引)

## 目录与已有 rootfs

默认 Linux 存储目录为 `$HOME/.local/share/pdn/rootfs`。也可以明确指定宿主有权限访问的目录：

```sh
export PDN_ROOTFS_DIR="$HOME/linux"
pdn list --available
pdn install alpine
pdn login alpine
```

Android shell 的 `HOME` 可能是 `/`，不要照搬这个目录示例；在 shell 权限下可选择 `/data/local/tmp/pdn/linux`，同时明确设置可写的 `PROOT_TMP_DIR`。详细步骤见 [Android shell 教程](pdn-shizuku-android-shell.md)。

rootfs 需要支持 Unix 权限和符号链接的文件系统，不能直接解压到 `/sdcard`。共享存储适合放归档、备份和供 Linux 访问的文件。管理操作前先退出相应 Linux 会话。

```sh
pdn install Ubuntu
pdn install debian
pdn install arch
pdn ls
pdn login ubuntu
```

发行版名称和命令支持 ASCII 大小写。已有 rootfs 可以放到 `$PDN_ROOTFS_DIR/名字/`，也可以直接登录指定目录：

```sh
pdn login --rootfs /完整/rootfs/路径
```

## 安装与下载源

| 名称 | 固定版本 | 架构 |
| --- | --- | --- |
| Alpine | 3.24.2 | ARM64 |
| Ubuntu Base | 24.04.5 LTS | ARM64 |
| Debian slim | 13 trixie，20261005 | ARM64 |
| Arch Linux ARM | 2026.08 | ARM64 |

四个发行版均内置 `official` 源。Alpine、Ubuntu、Arch 默认先尝试国内镜像，失败后回退官方。Debian 只保留一个官方 Docker rootfs 构建入口；GitHub `/raw/` 地址重定向到同一个文件，不是独立备用源。

| 发行版 | 官方来源 |
| --- | --- |
| Alpine | [Alpine CDN](https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/) |
| Ubuntu | [Ubuntu Base](https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/) |
| Debian | [debuerreotype 官方 Docker 构建](https://github.com/debuerreotype/docker-debian-artifacts) |
| Arch | [Arch Linux ARM 官方镜像](https://fl.us.mirror.archlinuxarm.org/os/multi/) |

```sh
pdn mirrors alpine
pdn install alpine --mirror official
pdn mirrors ubuntu
pdn install ubuntu --mirror tuna
pdn install alpine --archive /路径/对应固定版本的rootfs.tar.gz
```

指定镜像后只使用该源。下载检查内置的固定文件大小和 SHA256，通过后才解压，不会自动改用未经校验的 `latest` 包。`install --archive` 也必须匹配内置版本的校验值，不是任意归档导入。具体来源和校验值见 [rootfs 来源目录](pdn-rootfs-sources.md)。

目前没有镜像延迟测速排名或断点续传。Arch 压缩包约 791 MiB，安装时请预留数 GiB 空间。Kotlin 可以通过 `pdn.install("alpine", mirror = "official")` 生成操作；它返回 `ProcessBuilder`，还需启动或交给异步任务接口。

## 执行、挂载与默认配置

```sh
pdn exec ubuntu -- /usr/bin/id
pdn exec ubuntu -- /bin/sh -c 'echo hello; uname -r'
pdn login ubuntu --bind /sdcard:/mnt/shared
pdn config ubuntu --bind /sdcard:/mnt/shared --work-dir /root --env LANG=C.UTF-8
pdn login ubuntu
```

`--bind` / `-b` 可重复指定。Linux 修改挂载文件会直接修改宿主文件。临时挂载只对当前会话有效；`config` 保存的挂载会在后续登录与执行时自动加载。

```sh
pdn config ubuntu --show
pdn login ubuntu --no-config
pdn config ubuntu --clear
pdn login ubuntu --user root --work-dir /tmp --env EXAMPLE='two words'
```

每次 `config` 保存都会替换整套默认配置。命令行的账号、工作目录和环境变量覆盖默认值，挂载则追加。`--user` 使用 Linux 中已有的账号名或数字 UID，可带数字 GID；不会创建账号。默认 root 是 PRoot 模拟身份，不会提升 Android 的实际权限。

App 接入配置与命令执行见 [App 接入教程](android-embedding.md) 和 [AAR 接口](pdn-aar-api.md)。

## 备份与恢复

先退出该 Linux 会话，再执行：

```sh
pdn backup ubuntu /sdcard/ubuntu.tar.gz
pdn restore ubuntu-copy /sdcard/ubuntu.tar.gz
pdn login ubuntu-copy
```

备份文件必须位于源 rootfs 之外，恢复必须使用新名字；两者都不会覆盖已有目标。备份保留 Linux 文件数据及 PRoot 内部链接的迁移关系，排除宿主启动配置、临时 loader、运行时目录内容和特殊节点。换 App 后需重新设置挂载目录。

目录会补齐 owner rwx 权限，不恢复宿主所有者及 setuid/setgid。这是适合无 root 环境迁移的 rootfs 备份。归档限制、中断处理及更多参数见 [完整手册](proot-distro-nolib.md)。

## 指令索引

| 指令 | 用途 |
| --- | --- |
| `install` | 安装内置发行版 |
| `mirrors` | 查看 rootfs 下载源 |
| `list` / `ls` | 查看已安装系统；`--available` 查看可安装版本 |
| `login` | 交互登录，也支持 `-- COMMAND` |
| `exec` | 执行指定 Linux 命令 |
| `config` | 保存、查看、清除默认启动参数 |
| `backup` / `restore` | 备份与恢复到新名字 |
| `uninstall` / `remove` | 确认后删除系统及全部数据；`--yes` 跳过确认 |
| `version` / `help` | 查看版本、帮助 |
| `proot` | 直接使用底层 PRoot 参数 |

结构化查询支持 `list --json`、`list --available --json` 和 `mirrors 名称 --json`。GUI 可使用 AAR 的类型化查询及事件接口，无需解析供人阅读的终端文本。

## 安装别名与元数据

```sh
pdn install alpine --name ai-python --mirror official
pdn install alpine --archive /path/alpine.tar.gz --name ai-node
pdn exec ai-python -- /bin/sh -c 'echo hello'
pdn list --json
pdn backup ai-python /path/ai-python.tar.gz
pdn restore ai-restored /path/ai-python.tar.gz
```

新安装实例在 `.pdn-instance` 中保存稳定 ID 与来源信息。旧 rootfs 的 JSON `instance` 为 null，查询只读。restore 生成新的实例身份并保留可知的来源；名称已存在时拒绝覆盖。安装别名不提供直接 clone 或 rename。
