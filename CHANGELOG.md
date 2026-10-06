# proot-distro-nolib 更新记录

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
详细来源与摘要见 [rootfs 目录](docs/pdn-rootfs-sources.md)。用户已验证 Ubuntu 安装与登录；附加组名称警告在 v0.4.1 修复。

## v0.3.2 — 2026-10-06

- 新增 `pdn uninstall NAME`，别名 `pdn remove NAME`，命令和发行版名称忽略 ASCII 大小写。
- 卸载前显示实际目录，只有输入 `y` / `yes` 才删除；`--yes` / `-y` 可用于已有确认流程的 App 调用。
- 可删除配置目录下手动部署的发行版；卸载包含整个 rootfs 及其中的用户文件。
- 拒绝路径穿越、歧义名称、根目录符号链接和 `/` 父目录；内部符号链接只删除链接，拒绝跨文件系统遍历。
- 与安装共用操作锁，新版登录会话持有目录锁，使用中的系统拒绝卸载；不会强制结束会话。
- 删除失败返回非零并说明可能已经部分删除；不提供撤销。旧版登录和直接调用底层 PRoot 不参与会话锁，卸载前须退出。

验证：51 项自动测试通过；前端行覆盖率 98.64%，新增卸载模块 94.59%。
覆盖确认取消、EOF、链接目标保护、只读目录、权限失败、并发锁及确认期间目录被替换。
测试只操作临时 rootfs；用户于 2026-10-06 确认本版在 MT 管理器试用成功。

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
用户于 2026-10-06 确认 v0.3.0 在 MT 管理器中试用成功。

功能实现提交：`8948b58`。
ARM64 `pdn` / `proot-distro-nolib` 产物 SHA256：

```text
33d316cefc00535334989fa8b95b86dd393a85b67bf87882d0f55edb5628b8e2
```
