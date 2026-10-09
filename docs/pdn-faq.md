# 常见问题

简体中文 | [English](pdn-faq.en.md) · [返回 README](../README.md)

## 目录

- [下载了却不能执行](#下载了却不能执行)
- [版本仍显示旧版本](#版本仍显示旧版本)
- [rootfs 或临时目录不可用](#rootfs-或临时目录不可用)
- [App 怎样打包程序](#app-怎样打包程序)
- [Ubuntu 里的 rish 不能执行](#ubuntu-里的-rish-不能执行)
- [Shizuku 在后台断连](#shizuku-在后台断连)
- [root 与 Android 权限](#root-与-android-权限)
- [X11 目录指向自己](#x11-目录指向自己)
- [如何反馈错误](#如何反馈错误)

## 下载了却不能执行

先确认文件是 ARM64 Android 的 PDN 成品，而不是源码或 rootfs。共享存储可能禁止执行；`chmod 755` 不能改变挂载及 SELinux 限制。复制到宿主允许执行的目录，或者由 App 打包进 `nativeLibraryDir`。

```sh
chmod 755 /允许执行的目录/pdn
/允许执行的目录/pdn version
```

程序不在 `PATH` 时要用完整路径。MT 示例路径是 `/data/user/0/bin.mt.plus/files/term/bin/pdn`，只适用于 MT 自己的终端；Android shell 无权自动访问这个 App 私有目录。shell 权限下可以使用 `/data/local/tmp`，见 [教程](pdn-shizuku-android-shell.md)。

## 版本仍显示旧版本

下载新文件不会替换其他目录里复制过的旧文件，AAR 或 APK 内也可能仍有旧程序。先查实际调用位置：

```sh
command -v pdn
pdn version
pdn --version
```

退出旧会话后同步替换 PDN、loader、`.so` 或 AAR，再核对版本。App 接入还需重新打包安装。`Based on PRoot 5.4.0-pr` 是底层版本，不是 PDN 的版本号。

## rootfs 或临时目录不可用

Android shell 的 `HOME` 可能是 `/`，而该目录不可写。明确指定有权限的目录；不要把 rootfs 放到 `/sdcard`。例如在 UID 2000 的 Android shell 中：

```sh
export PDN_ROOTFS_DIR=/data/local/tmp/pdn/linux
export PROOT_TMP_DIR=/data/local/tmp/pdn/tmp
mkdir -p "$PROOT_TMP_DIR"
```

报错中的具体路径、原因和建议比盲目 `chmod 777` 更有用。权限之外还可能有空间不足、文件占用了目录名或文件系统不支持所需语义等原因。PDN 不会在失败后擅自换存储位置；详细错误分类见 [测试与错误记录](pdn-error-testing.md)。

## App 怎样打包程序

AAR 提供 Java/Kotlin 封装、PDN、loader 和 PTY JNI。宿主需要 Kotlin 标准库、适当权限和自己的界面；AAR 不内置完整发行版或现成终端 GUI。

直接 `.so` 接入时，把 `libpdn.so`、`libproot-loader.so` 放到 `jniLibs/arm64-v8a/`，确保 Android 将其解压到 `nativeLibraryDir`，通过进程启动。它们是可执行 ELF，不能当作 PDN JNI 接口用 `System.loadLibrary` 加载。实际 PTY JNI 库用于终端输入输出及调整大小。

见 [App 接入教程](android-embedding.md)、[AAR API](pdn-aar-api.md)、[AAR 示例](../examples/aar-probe/README.md) 和 [.so 示例](../examples/so-probe/README.md)。

## Ubuntu 里的 rish 不能执行

原始 Shizuku rish 脚本指定 `/system/bin/sh`，还需要 `app_process` 和对应 dex。Ubuntu 中不存在这些 Android 路径时，文件即使存在、可执行，也会提示解释器找不到。

已验证的流程是先在宿主连接原始 rish，再从 Android shell 启动 PDN，让 Linux 继承其权限，并挂载所需 Android 路径：

```sh
./pdn login ubuntu --bind /system --bind /apex --bind /linkerconfig/ld.config.txt
```

本次测试的运行环境具备上述路径；其他系统需要核对实际布局。在 Ubuntu 中把 Android shell 调用封装成函数：

```sh
rish() {
    /system/bin/sh -c 'export PATH=/system/bin:/system/xbin; exec /system/bin/sh "$@"' -- "$@"
}
rish -c 'getprop ro.build.version.sdk'
```

这是自定义函数，不是嵌套连接 Shizuku 的原始客户端。已验证 Android 查询命令及临时 `settings` 写入；没有据此宣称 Ubuntu 自带的 `adb` 客户端已经验证。初次 rish 准备、函数持久化、写入验证和清理见 [完整教程](pdn-shizuku-android-shell.md)。

## Shizuku 在后台断连

本次 MT 复现中，获得 Shizuku 授权的宿主进入后台会断连，即使电池设为“无限制”仍然发生。照此教程操作时，保持被授权宿主全程在前台；断连后检查 Shizuku 状态并重新连接。

这是已观察到的环境行为，不是所有 Android ROM 的统一机制。不要把授予权限或取消电池限制当作本次流程能在后台保持连接的保证。

## root 与 Android 权限

Ubuntu 提示符显示 root 是 PRoot 模拟身份。它不能把普通 App 提升为 Android root，也不能绕过 SELinux。Shizuku/rish 在本次流程提供的是 UID 2000 的 Android shell 权限；Linux 从启动时继承该权限，后续可用操作仍受系统限制。

PRoot 不提供容器级安全隔离。Linux 对挂载目录的修改可能直接作用于宿主文件，执行不可信程序前需考虑其可访问范围。

## X11 目录指向自己

部分发行版保留 `/usr/bin/X11 -> .` 历史兼容符号链接。它指向当前目录，不是 PDN 自我 bind，也不表示装了图形桌面。检查：

```sh
ls -ld /bin/X11 /usr/bin/X11
readlink /usr/bin/X11
```

## 如何反馈错误

在 [Issues](https://github.com/EMERLADD/proot-distro-nolib/issues) 提供宿主 App、Android 版本、PDN 版本、复现命令和完整错误输出。不要贴密码、Token、设备序列号或其他隐私信息。出现原生崩溃时，仅凭缺少解释器的报错不能确定崩溃来自哪个进程，应保留完整输出单独排查。
