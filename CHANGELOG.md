# proot-distro-nolib 更新记录

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
