# 在 MT 管理器终端部署 PDN ELF

简体中文 | [English](pdn-shell.en.md) · [返回 README](../README.md)

本页以 **MT 管理器自带的普通终端**为例，把原始 ELF 放进 App 私有目录，配置 Linux 存储位置，再注册 `pdn` 命令。安装和运行 Linux 不需要先进入 rish，也不需要 Termux。其他允许执行 ELF 的 Android 终端可以使用同样的方法，目录换成该 App 自己能访问的位置。

如果要从 Linux 调用具有 Android shell 权限的系统调试命令，另见 [调试 Android](pdn-shizuku-android-shell.md)。

## 1. 准备文件和终端

从 [Releases](https://github.com/EMERLADD/proot-distro-nolib/releases) 下载同一版本的 ARM64 Android `pdn` 和 `proot-loader`，核对 `SHA256SUMS`。下载到 `/sdcard/Download/` 即可；共享存储用于存放下载文件，程序和 rootfs 部署到私有目录。

打开 MT 管理器自带终端，确认所在环境：

```sh
id
pwd
```

下面在 **MT 原来的终端**执行。示例使用包名 `bin.mt.plus` 的私有目录；如果你的 MT 包名或终端目录不同，改成实际目录。已经进入 rish 时，先 `exit` 回到 MT 原来的终端，才能访问 MT 私有目录。

## 2. 把 ELF 放进私有目录

```sh
cd /data/user/0/bin.mt.plus/files/term
mkdir -p pdn
cd pdn
cp /sdcard/Download/pdn .
cp /sdcard/Download/proot-loader .
chmod 700 pdn proot-loader
./pdn version
```

也可以通过 MT 的文件管理界面把两个文件复制到同一目录，再执行 `chmod` 和版本检查。文件名保留为 `pdn`、`proot-loader`；这是独立程序，不需要改成 `.so` 或安装成 APK。

如果源文件不在 Download，修改两条 `cp` 的源路径。私有目录不能访问时，检查是否仍在 MT 普通终端、包名及路径是否正确；Shizuku/ADB shell 的 `/data/local/tmp` 部署属于另一条入口。

## 3. 创建固定目录的启动入口

仍在刚才的 `pdn` 目录中，逐行执行：

```sh
cat > pdn-run <<'EOF'
#!/system/bin/sh
PDN_DIR=$(dirname "$0")
PDN_DIR=$(cd "$PDN_DIR" && pwd) || exit 1
export PDN_ROOTFS_DIR="$PDN_DIR/linux"
export PROOT_TMP_DIR="$PDN_DIR/tmp"
export PROOT_LOADER="$PDN_DIR/proot-loader"
mkdir -p "$PROOT_TMP_DIR" || exit 1
exec "$PDN_DIR/pdn" "$@"
EOF
chmod 700 pdn-run
```

入口从自己的位置确定目录，每次启动都设置 rootfs、临时目录和 loader 路径，不依赖外层 shell 的 `HOME` 或当前工作目录。安装操作创建 `linux`，入口创建 `tmp`：

```text
files/term/pdn/
├── pdn
├── proot-loader
├── pdn-run
├── pdn-command.sh
├── linux/
└── tmp/
```

已有 Linux 时，`PDN_ROOTFS_DIR` 应沿用原来的管理目录，避免新入口看不到已安装系统。不要把 rootfs 指向 `/sdcard`。

`EOF` 必须独占一行；结束前的 `>` 表示 shell 正在等待文件内容。变量赋值各自一行，文件名直接写 `pdn-run`，不需要把长目录和文件名拼在一起。

## 4. 注册 pdn 短命令

在同一目录创建并加载定义文件：

```sh
cat > pdn-command.sh <<'EOF'
pdn() {
    /data/user/0/bin.mt.plus/files/term/pdn/pdn-run "$@"
}
EOF
chmod 600 pdn-command.sh
. ./pdn-command.sh
pdn version
```

函数转发参数给启动入口，之后可直接输入 `pdn login alpine`。换部署目录时，修改函数里的入口路径。`./pdn` 会直接执行原始 ELF；使用固定目录的入口请写 `pdn` 或 `./pdn-run`。

定义文件会保留，函数需在每次新终端会话中加载：

```sh
. /data/user/0/bin.mt.plus/files/term/pdn/pdn-command.sh
```

如果终端支持启动时执行命令，把这一行配置进去即可。不要假定 Android shell 会自动读取 `~/.bashrc` 或 `~/.profile`。文件只需可读，必须用点命令加载；直接执行它不会把函数注册到当前 shell。

## 5. 安装并进入 Linux

```sh
pdn list --available
pdn install alpine
pdn login alpine
```

进入后可运行 Linux 命令，`exit` 返回 MT 终端。也可以安装并登录 Ubuntu：

```sh
pdn install ubuntu
pdn login ubuntu
```

这里的 Linux root 是 PRoot 模拟身份，仍受 MT 的 Android UID 和 SELinux 权限限制。发行版来源、续传、命令执行、实例管理和备份恢复见 [发行版管理](pdn-distributions.md)；完整选项见 [CLI 手册](proot-distro-nolib.zh-CN.md)。

## 6. 更新与排查

更新时先退出 Linux 会话，把同一新版本的 `pdn`、`proot-loader` 复制到上述程序目录，再执行 `chmod 700 pdn proot-loader` 并检查 `pdn version`。保留 `linux` 和两个入口文件，已有系统不需要重装。

| 现象 | 检查方法 |
| --- | --- |
| `pdn: not found` | 加载 `pdn-command.sh`；函数仅在当前 shell 中生效 |
| 找不到 `pdn-command.sh` | 先完成文件创建，再在程序目录执行 `. ./pdn-command.sh` |
| `can't create …: Is a directory` | 重定向目标必须是文件；在程序目录使用 `cat > pdn-command.sh` |
| `mkdir: '': No such file or directory` | 用 `cat pdn-run` 检查变量赋值是否粘贴完整 |
| rootfs 路径只读或没有权限 | 检查是否使用了入口，以及 `PDN_ROOTFS_DIR` 是否为当前宿主可写目录 |
| ELF 无法执行 | 确认使用 ARM64 Android 文件、已设置执行权限，且程序位于宿主允许执行的私有目录 |

系统若禁止当前 App 从私有目录执行 ELF，需要换允许执行的宿主入口；其他 App 的私有目录不会因为复制文件或 PRoot 模拟 root 而变得可访问。
