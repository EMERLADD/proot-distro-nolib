# 在 Android shell 中使用 PDN

简体中文 | [English](pdn-shell.en.md) · [返回 README](../README.md)

先从 [Releases](https://github.com/EMERLADD/proot-distro-nolib/releases) 下载同一版本的原始 `pdn` 和 `proot-loader`。本页介绍在有执行和存储权限的 shell 中部署、安装和管理 Linux；如果要在 Linux 内调用 Android 调试命令，见 [Android 调试教程](pdn-shizuku-android-shell.md)。

## 在 Android shell 部署程序

下面适用于允许从 `/data/local/tmp` 执行程序的 Android shell，例如 ADB 或已启动的 Shizuku/rish shell。普通终端 App 应使用自己允许执行和读写的目录；不要在 Android shell 中访问 App 私有目录初始化原始 rish。

下载 `pdn` 和 `proot-loader` 后，按实际位置修改两条 `cp` 的源路径：

```sh
mkdir -p /data/local/tmp/pdn
cp /sdcard/Download/pdn /data/local/tmp/pdn/pdn
cp /sdcard/Download/proot-loader /data/local/tmp/pdn/proot-loader
chmod 755 /data/local/tmp/pdn/pdn /data/local/tmp/pdn/proot-loader
cd /data/local/tmp/pdn
./pdn version
```

指定 Linux 和临时目录：

```sh
export PDN_ROOTFS_DIR=/data/local/tmp/pdn/linux
export PROOT_TMP_DIR=/data/local/tmp/pdn/tmp
export PROOT_LOADER=/data/local/tmp/pdn/proot-loader
mkdir -p "$PROOT_TMP_DIR"
```

当前工作目录不会自动决定 rootfs 位置。Android shell 的 `HOME` 可能是 `/`，因此应显式设置目录。完整步骤和 MT 示例见[Android shell 教程](pdn-shizuku-android-shell.md)。

AAR 支持系统文件选择器返回的 URI、FD 和流，用于压缩包导入与备份导出；示例 App 提供选择器按钮，详见 [AAR API](pdn-aar-api.md)。

中断安装可用 `pdn recovery list` 检查、`pdn recovery clear --yes` 清理明确中断的暂存；已安装系统、未知条目和续传缓存保留，详见 [发行版管理](pdn-distributions.md#中断安装检查与清理)。

## 保存目录设置，供以后使用

`export` 只在当前 shell 及其子进程中有效。要以后每次使用相同目录，可以用命令创建一个专用入口；在外层 Android shell 执行：

```sh
cat > /data/local/tmp/pdn/pdn-run <<'EOF'
#!/system/bin/sh
export PDN_ROOTFS_DIR=/data/local/tmp/pdn/linux
export PROOT_TMP_DIR=/data/local/tmp/pdn/tmp
export PROOT_LOADER=/data/local/tmp/pdn/proot-loader
mkdir -p "$PROOT_TMP_DIR" || exit 1
exec /data/local/tmp/pdn/pdn "$@"
EOF
chmod 700 /data/local/tmp/pdn/pdn-run

/data/local/tmp/pdn/pdn-run install alpine
/data/local/tmp/pdn/pdn-run login alpine
```

以后重新进入 rish，直接执行上面的 `pdn-run` 绝对路径即可，入口每次自动设置变量，不需要再次 export。原始 `pdn` 文件保留；直接执行 `./pdn` 不会自动读取这个脚本。按实际部署目录修改所有 `/data/local/tmp/pdn`，例如本页 MT 场景可使用 `/data/local/tmp/pdn-mt`。

如果希望当前会话直接输入 `pdn`，也可以保存 `pdn-env.sh` 并用点命令加载，完整命令见 [保存环境及登录入口](pdn-shizuku-android-shell.md#固定目录变量和专用登录入口)。原生 Android shell 不保证自动读取 `~/.bashrc` 或 `~/.profile`。

### 直接使用 pdn 短命令

已有上面的 `pdn-run` 后，在外层 Android shell 定义一个函数，之后就能直接输入 `pdn login alpine`：

```sh
pdn() { /data/local/tmp/pdn/pdn-run "$@"; }
pdn install alpine
pdn login alpine
```

函数名只在当前 shell 生效。要保存这个短命令，创建定义文件并加载：

```sh
cat > /data/local/tmp/pdn/pdn-command.sh <<'EOF'
pdn() { /data/local/tmp/pdn/pdn-run "$@"; }
EOF
chmod 600 /data/local/tmp/pdn/pdn-command.sh
. /data/local/tmp/pdn/pdn-command.sh
pdn login alpine
```

重新连接 rish 后，执行 `. /data/local/tmp/pdn/pdn-command.sh` 恢复短命令。若终端或连接工具支持初始化命令，将这一行配置为**进入 rish 后**执行；Android shell 不保证自动加载这个文件。保存的是函数定义，`pdn-run` 每次负责设置 rootfs 和临时目录。MT 示例部署在 `/data/local/tmp/pdn-mt` 时，替换代码里的目录即可。`./pdn` 明确指定文件，会直接运行原始程序；使用短命令请写 `pdn`。

## 安装、登录和管理发行版

```sh
./pdn list --available
./pdn install ubuntu
./pdn login ubuntu
```

退出 Linux 后，在宿主 shell 中执行一次命令或管理系统：

```sh
./pdn exec ubuntu -- /bin/sh -c 'echo hello; uname -r'
./pdn mirrors ubuntu
./pdn list
```

在线下载中断或取消后，保持相同 rootfs 管理目录并重复同一安装操作，会自动尝试从相同镜像 URL 续传；不会在后台自动重试。完整大小和 SHA256 校验通过后才解压，详见[发行版管理教程](pdn-distributions.md)。

支持 Alpine、Ubuntu Base、Debian slim 和 Arch Linux ARM。官方源选择、离线归档、挂载、账号、默认配置、备份恢复和完整指令索引见[发行版管理教程](pdn-distributions.md)。

同一发行版可以安装成不同实例；各自拥有独立 rootfs：

```sh
./pdn install alpine --name ai-python
./pdn install alpine --name ai-node
./pdn login ai-python
./pdn list --json
```

名称最多 128 个 ASCII 字符，首字符为字母、数字或下划线，后续还允许点和短横线；名称冲突不区分大小写。新实例记录稳定 ID、来源、版本、摘要和创建时间。旧 rootfs 查询为 `instance: null`，不会自动改写；备份恢复为新实例时生成新 ID。

复制实例会生成新 ID；重命名保留原 ID，并迁移属于原 rootfs 的链接与保存的挂载配置。操作前退出实例会话：

```sh
./pdn clone ai-python ai-python-test
./pdn rename ai-python-test workspace-python
./pdn login workspace-python
```

