# 固定 ARM64 rootfs 来源

简体中文 | [English](pdn-rootfs-sources.md) · [返回 README](../README.md)

本文是仓库内英文来源目录的中文说明。归档大小、摘要与来源核验记录截至 2026-10-06；当前程序内置同一组固定归档。它不是上游最新版本列表。

PDN 直接下载发行版上游的 rootfs，不依赖 Termux 构建或运行文件。归档不内嵌到程序，也不提交到仓库。预期大小和 SHA256 编译进程序，下载镜像不能在运行时决定接受哪个摘要。

| 名称 | 归档 | 字节数 | SHA256 |
| --- | --- | ---: | --- |
| alpine | alpine-minirootfs-3.24.2-aarch64.tar.gz | 4028030 | `9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773` |
| ubuntu | ubuntu-base-24.04.5-base-arm64.tar.gz | 29936675 | `a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2` |
| debian | trixie/slim/oci/blobs/rootfs.tar.gz | 30200282 | `bbeda6b4abb749f743f4547ef5a28070a14de8caef211daa5afb78d5b5fa21ba` |
| arch | ArchLinuxARM-2026.08-aarch64-rootfs.tar.gz | 829367415 | `42a4eeaa038994ffd31fa173256ef2f0ef511358eeb41b9ea1f8626391b9b319` |

## Alpine

使用 [Alpine 官方 minirootfs](https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/) 的 ARM64 归档。当前回退顺序是 `tuna → ustc → nju → official → dotsrc`，所有来源都必须提供上述相同内容。

这是固定顺序回退，不是自动测速。安装后 apk 仓库跟随成功的下载源；离线安装采用第一个来源的仓库设置。

## Ubuntu

[Canonical Ubuntu Base 发布目录](https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/) 提供 ARM64 归档及 [SHA256SUMS](https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS)。核验时，下载归档的摘要与上游校验文件一致。

TUNA 和 USTC 镜像对应的 `ubuntu-cdimage/ubuntu-base/releases/24.04/release/` 路径，回退顺序为 `tuna → ustc → official`。系统内的软件仓库使用 ARM64 对应的 `ubuntu-ports`，不能替换成 amd64 的 Ubuntu archive。

## Debian

来源为 [debuerreotype/docker-debian-artifacts](https://github.com/debuerreotype/docker-debian-artifacts)，即 Debian 官方容器镜像使用的 rootfs 构建产物。

当前固定 arm64v8 构建提交 `cf1f4a45447842b45e9952e0f018ae734a7341c7`，构建日期为 20261005、debuerreotype 版本为 0.17，文件为 `trixie/slim/oci/blobs/rootfs.tar.gz`。归档大小与 SHA256 已与[固定提交的 OCI manifest](https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/cf1f4a45447842b45e9952e0f018ae734a7341c7/trixie/slim/oci/blobs/image-manifest.json) 中的 layer 描述核对。

安装只取 rootfs 的 gzip 层，PDN 不因此成为通用 OCI 客户端。目前只配置一个 `official` 入口，指向不可变提交。GitHub `/raw/` 地址若重定向到同一个文件，不能算独立备用源；没有配置经过核验的独立国内镜像。

## Arch Linux ARM

[官方下载说明](https://archlinuxarm.org/about/downloads) 列出 AArch64 平台与发布签名密钥。当前使用 `os/multi/ArchLinuxARM-2026.08-aarch64-rootfs.tar.gz`，而非会改变内容的 `latest` 文件名。

归档的分离签名已使用[官方构建系统密钥](https://archlinuxarm.org/about/package-signing) 核验，指纹为 `68B3537F39A313B3E574D06777193F152BDBE6A6`；SHA256 固定为上表的值。

核验时，TUNA、USTC、NJU 与美国 Florida 镜像通过 HTTPS 返回了相同大小的日期归档。自动 GeoIP 入口曾因 TLS 主机名校验失败而被排除，没有关闭 TLS 校验。当前顺序为 `tuna → ustc → nju → official`。

这是完整上游文件系统，含内核与固件包，解压后约 2 GiB；应为归档、rootfs 和更新预留数 GiB 空间。它是 Arch Linux ARM，不是 x86-64 Arch Linux。

## 查询、选择来源与离线安装

```sh
pdn mirrors
pdn mirrors debian
pdn install alpine --mirror official
pdn install ubuntu --mirror ustc
pdn install alpine --archive /路径/alpine-minirootfs-3.24.2-aarch64.tar.gz
```

显式指定 `--mirror` 时只尝试该来源；不指定时按固定顺序回退。`--archive` 必须匹配对应发行版的固定大小与摘要，不能与 `--mirror` 同时使用，也不是任意归档导入接口。自备 rootfs 的登录和备份恢复见[中文 CLI 手册](proot-distro-nolib.zh-CN.md)。

## 更新固定归档

更新来源时，应核验上游身份、架构、签名或校验描述，记录准确大小与 SHA256，检查归档布局，并验证安装、登录和包管理操作，再修改程序目录。

上游删除旧归档时，安装会失败，需要更新目录；程序不会静默接受新的校验值。发行版内各软件的许可仍由各自上游决定。完整历史验证见[测试记录](pdn-error-testing.md)。
