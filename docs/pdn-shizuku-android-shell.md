# 通过 Android shell 启动 Ubuntu，并调用 Android 命令

简体中文 | [English](pdn-shizuku-android-shell.en.md) | [返回 README](../README.md)

适用于能运行 PDN、访问所选目录的 Android shell，不绑定某个终端 App。下面以 MT 管理器和 Shizuku/rish 为例；已有 ADB shell 也可从部署 PDN 开始。所有权限仍取决于启动 PDN 的真实 Android 身份及系统限制。

2026-10-09，PDN 0.6.6、Ubuntu Base 24.04.5 LTS、ARM64 Android：先通过 Shizuku 获得真实 `uid=2000(shell)`，再启动 Ubuntu，验证 Android 属性、包管理查询、临时设置写入及退出 Ubuntu 后的读取和删除。此链路在 Termux 授权入口实测，并在 MT 管理器中复现成功。自定义命令的交互模式、引号及空格参数、重新登录后的持久化在本地测试中验证；不代表所有 Android 命令和系统都已测试。

## 目录

- [三个终端环境](#三个终端环境)
- [前台与连接要求](#前台与连接要求)
- [进入 Android shell](#进入-android-shell)
- [部署 PDN 和安装 Ubuntu](#部署-pdn-和安装-ubuntu)
- [带挂载进入 Ubuntu](#带挂载进入-ubuntu)
- [把长命令注册为 rish](#把长命令注册为-rish)
- [验证 Android 设置并清理](#验证-android-设置并清理)
- [下次进入与删除环境](#下次进入与删除环境)
- [常见问题](#常见问题)

## 三个终端环境

```text
宿主 App 原来的终端
  → 原始 rish 客户端连接 Shizuku
  → Android shell，真实 UID 通常为 2000
  → PDN 启动 Ubuntu
  → Ubuntu 内调用 Android shell 和系统命令
```

| 环境 | 在这里做什么 |
| --- | --- |
| MT 原来的终端 | 初次配置原始 rish，访问 MT 自己的私有目录 |
| Shizuku/ADB 的 Android shell | 部署 PDN，设置目录，安装和启动 Ubuntu |
| Ubuntu 的 Bash | 定义自选命令，运行 Linux 程序或调用 Android 命令 |

Ubuntu 提示符显示 root，是 PRoot 模拟身份；底层进程继承启动 PDN 时的 Android 权限。无线调试模式的 Shizuku 通常提供 shell 权限，不会因此获得 Android 真 root。

Ubuntu 内后面定义的 `rish` 是 Bash 函数，使用已经继承的权限调用 `/system/bin/sh`，不会再次连接 Shizuku。原始 rish 客户端在本次 shell 内嵌套启动仍超时，因此教程采用函数封装。

## 前台与连接要求

使用期间，把获得 Shizuku 授权、负责启动原始 rish 的宿主 App 保持在前台。本次环境观察到：宿主进入后台会断连，即使电池策略设为“无限制”也不能保证连接持续。其他系统可能不同，复现时按此要求操作。

重启 Shizuku 后，重新从宿主终端启动原始 rish，再进入 Ubuntu；已安装的 Ubuntu 不需要重装。

代码块不包含提示符，复制时不要额外带上 `$` 或 `root@localhost:~#`。第一次按步骤逐段执行，不要把属于不同环境的代码一次粘贴。

## 进入 Android shell

**如果宿主终端已经能运行 `rish`，直接执行它，再执行 `id`；跳过下面的初次配置。**

```sh
rish
id
```

无线调试模式下应看到 `uid=2000(shell)`，通常还有 `u:r:shell:s0`。如果已经处于该身份，不要再访问 MT 私有目录配置 rish，直接进入下一节。ADB shell 也可从下一节开始。

### 尚未配置原始 rish 时

在 Shizuku 的“在终端应用中使用 Shizuku”入口导出 `rish` 和 `rish_shizuku.dex`。例如手动存到：

```text
/sdcard/yyd/rish-source/rish
/sdcard/yyd/rish-source/rish_shizuku.dex
```

目录和文件需要自己导出准备，不会自动生成。Android 14 起 app_process 加载的 dex 必须不可写，因此先复制到宿主私有目录，再设置权限。

以下以 MT 包名 `bin.mt.plus` 为例，其他版本或 App 必须换成实际包名及其可访问的私有目录。在 **MT 原来的终端，尚未进入 rish 时**执行：

```sh
MT_RISH_DIR=/data/user/0/bin.mt.plus/files/pdn-rish
mkdir -p "$MT_RISH_DIR"
cp /sdcard/yyd/rish-source/rish "$MT_RISH_DIR/rish"
cp /sdcard/yyd/rish-source/rish_shizuku.dex "$MT_RISH_DIR/rish_shizuku.dex"
chmod 700 "$MT_RISH_DIR"
chmod 700 "$MT_RISH_DIR/rish"
chmod 400 "$MT_RISH_DIR/rish_shizuku.dex"
export RISH_APPLICATION_ID=bin.mt.plus
sh "$MT_RISH_DIR/rish"
```

如果导出的脚本要求修改其中的 `PKG`，按 Shizuku 导出说明填入实际宿主应用 ID。确认 Shizuku 已授权该 App，进入后执行 `id` 核对真实身份。

这个初次配置不是本次 MT 复现的验收内容；MT 复现从已有可用 rish 开始。Android shell 无权访问 MT 私有目录时，退回 MT 原来的终端再做配置。

## 部署 PDN 和安装 Ubuntu

以下在 **Android shell** 执行。下载同一 Release 的 `pdn` 和 `proot-loader`；示例假设文件位于 `/sdcard/yyd/PDN/`，实际位置不同就修改两条 `cp` 的源路径。

```sh
mkdir -p /data/local/tmp/pdn-mt
cp /sdcard/yyd/PDN/pdn /data/local/tmp/pdn-mt/pdn
cp /sdcard/yyd/PDN/proot-loader /data/local/tmp/pdn-mt/proot-loader
chmod 755 /data/local/tmp/pdn-mt/pdn
chmod 755 /data/local/tmp/pdn-mt/proot-loader
cd /data/local/tmp/pdn-mt
./pdn version
./pdn --version
```

两个版本命令都应显示当前 PDN 版本，本次为 0.6.6。`Based on PRoot 5.4.0-pr` 是底层版本。

```sh
export PDN_ROOTFS_DIR=/data/local/tmp/pdn-mt/linux
export PROOT_TMP_DIR=/data/local/tmp/pdn-mt/tmp
export PROOT_LOADER=/data/local/tmp/pdn-mt/proot-loader
mkdir -p "$PROOT_TMP_DIR"
./pdn install ubuntu
```

等待下载、SHA256 验证和解压完成。不要提前创建 `linux/ubuntu`；已安装时跳过 `install`，直接登录。

`/data/local/tmp/pdn-mt` 是 shell 可写目录的示例，不是固定要求。选其他目录时，要同步修改程序、rootfs、临时目录和 loader 路径。不要把 rootfs 解压到 `/sdcard`。

## 带挂载进入 Ubuntu

在 **Android shell** 执行下面这一整行：

```sh
./pdn login ubuntu --bind /system --bind /apex --bind /linkerconfig/ld.config.txt
```

进入 Ubuntu 后检查：

```sh
head -n 2 /etc/os-release
grep '^Uid:' /proc/self/status
```

本次为 Ubuntu 24.04.5 LTS，实际 UID 为 2000；guest 的 `id` 会显示模拟 root。

三个挂载提供 Android 系统程序、APEX 运行库和动态链接器配置，仅作用于此次登录，之后也要带上。目录 `/linkerconfig` 可能不允许列出，但具体配置文件可以访问，本次直接挂载了文件。

这些挂载足够运行本次测试命令；其他程序或系统可能还需额外 Android 路径。

## 把长命令注册为 rish

在 **Ubuntu 内**执行下面整段。这里用 `--` 作为子 shell 的名称参数，避免引号与名字粘连：

```sh
rish() {
    /system/bin/sh -c 'export PATH=/system/bin:/system/xbin; exec /system/bin/sh "$@"' -- "$@"
}
```

先测试：

```sh
type rish
rish -c 'getprop ro.build.version.sdk'
rish -c 'cmd package path android'
```

`type rish` 应显示 `rish is a function`；本次 Android 命令输出 SDK `34` 和 `package:/system/framework/framework-res.apk`。SDK 数值以实际系统为准。

成功后在 Ubuntu 保存，**只追加一次**：

```sh
cat >> ~/.bashrc <<'EOF'
rish() {
    /system/bin/sh -c 'export PATH=/system/bin:/system/xbin; exec /system/bin/sh "$@"' -- "$@"
}
EOF
source ~/.bashrc
```

以后交互式登录 Ubuntu 时 Bash 会加载函数。想命名为 `ash`，就把 `rish()` 改为 `ash()`，后续用 `ash -c '命令'`。`"$@"` 保留参数边界，保持原样。

使用例子：

```sh
rish -c 'getprop ro.build.version.sdk; settings get global airplane_mode_on'
rish
```

直接输入 `rish` 会进入 Android 交互式 shell；输入 `exit` 返回 Ubuntu，再输入一次 `exit` 才退出 Ubuntu。函数只在加载它的 Bash 中可用，不是任意程序都能直接执行的磁盘文件。

这里给 Android 子 shell 单独设置 PATH，Ubuntu 自己的 PATH 不变，`apt` 等 Linux 命令继续照常使用。

## 验证 Android 设置并清理

在 **Ubuntu 内**先查专用测试项：

```sh
rish -c 'settings get global pdn_ubuntu_demo_20261009'
```

输出 `null` 表示尚不存在，是正常结果。如果它已有值，先换一个未使用的 `pdn_ubuntu_demo_` 名字，并在所有命令中保持一致。

```sh
rish -c 'settings put global pdn_ubuntu_demo_20261009 hello-from-ubuntu'
rish -c 'settings get global pdn_ubuntu_demo_20261009'
exit
```

回到 **外层 Android shell** 后核对并删除：

```sh
settings get global pdn_ubuntu_demo_20261009
settings delete global pdn_ubuntu_demo_20261009
settings get global pdn_ubuntu_demo_20261009
```

第一次应输出 `hello-from-ubuntu`，清理后应输出 `null`。这是 Android 真实设置数据库，不是 Ubuntu 中的配置文件；专用测试键不切换飞行模式等现有系统功能。

中途断连时，重连到 Android shell 后也要删除测试项。此验证在 MT 管理器中已复现成功。

## 下次进入与删除环境

从宿主终端重新连接原始 rish，进入 **Android shell** 后：

```sh
cd /data/local/tmp/pdn-mt
export PDN_ROOTFS_DIR=/data/local/tmp/pdn-mt/linux
export PROOT_TMP_DIR=/data/local/tmp/pdn-mt/tmp
export PROOT_LOADER=/data/local/tmp/pdn-mt/proot-loader
mkdir -p "$PROOT_TMP_DIR"
./pdn login ubuntu --bind /system --bind /apex --bind /linkerconfig/ld.config.txt
```

Ubuntu 内保存的函数自动加载，不需要重装 Ubuntu，也不需要把原始 rish 和 dex 复制进 Ubuntu。

测试结束，先退出 Android 子 shell 和 Ubuntu，确认没有需要保留的数据，再从 **外层 Android shell** 删除本教程创建的整个目录：

```sh
rm -rf /data/local/tmp/pdn-mt
```

这会删除其中的 Ubuntu、PDN 副本和临时文件，不会删除下载目录中的发布产物。原始 rish 若以后仍需使用，可以保留在宿主私有目录。

## 常见问题

| 现象 | 检查方向 |
| --- | --- |
| `Request timeout`、突然断连 | 宿主保持前台，检查 Shizuku 和授权；服务重启后重新连接。 |
| MT 私有目录报 `Permission denied` | 原始 rish 的初次配置应在 MT 原来的终端进行；已有可用 rish 则直接跳过。 |
| `rish-source` 不存在 | 它是手动导出示例目录；先导出文件或修改路径。 |
| `cannot access rootfs directory` | 重新设置 `PDN_ROOTFS_DIR`，不能沿用 `HOME=/` 的默认路径。 |
| `temporary directory unavailable` | 核对 `PROOT_TMP_DIR`，在 Android shell 中创建并检查权限。 |
| `rootfs already exists` | 已装过 Ubuntu，直接登录。 |
| `bash: rish: command not found` | Ubuntu 内尚未定义函数；当前会话定义后先测试，再保存 `.bashrc`。 |
| 提示符一直是 `>` | 单引号、双引号、函数大括号或 heredoc 尚未结束；按 Ctrl+C 取消，重新完整粘贴代码块。 |
| 命令末尾意外多出 `android` | 旧函数定义的引号与名字粘连；使用上面的 `--` 版本重新定义。 |
| `/usr/bin/rish: required file not found` | 原始脚本解释器不存在；按教程带挂载登录并使用函数封装。 |
| Android 动态库缺失或链接器配置警告 | 检查 APEX 和 linker 配置文件挂载；其他程序可能还需额外系统路径。 |
| 原始 rish 在 Ubuntu 误报 dex 可写 | PRoot 模拟 root 影响判断；在宿主配置客户端，Ubuntu 内使用已继承权限的函数。 |
| Android 命令权限不足 | 核对启动 PDN 前的真实 Android 身份；模拟 root 不增加宿主权限，shell 本身也有权限边界。 |
