# 从源码构建与发布文件

简体中文 | [English](pdn-build-and-release.en.md) · [返回 README](../README.md)

## 构建原始 ELF

独立 PDN 构建需要 Git、Make、Clang/LLVM、curl、tar、xz/bzip2、pkg-config 和 Android NDK；不需要 Java、Gradle 或 Rust。第一次构建会下载固定校验值的依赖源码。

```sh
git clone https://github.com/EMERLADD/proot-distro-nolib.git
cd proot-distro-nolib
git submodule update --init --depth 1 vendor/samba
make NDK_PATH=/path/to/android-ndk
```

Linux x86_64 主机可使用 NDK 自带 LLVM 工具；ARM64 Android 本机需使用能在 Android 上运行的 Clang/LLVM，配合 NDK sysroot。不能在 ARM64 Android 直接运行 NDK 的 Linux x86_64 编译器。构建工具可来自 Termux，产物运行不依赖 Termux。

输出位于 `build/proot-distro-nolib/arm64/`。更换工具链或依赖编译参数时执行 `make clean` 后重编译。

## 构建 AAR 和 App

AAR 还需要 Java、Gradle、Android SDK 和 PTY JNI 构建环境。先构建 PDN 与 loader，再执行：

```sh
cd android
sh gradlew -PpdnEngineOnly=true :proot-engine:bundleReleaseAar
```

原生文件准备、AAR 导入与 APK 打包见 [App 接入教程](android-embedding.md)。可运行的独立工程见 [AAR 示例](../examples/aar-probe/README.md)和 [.so 示例](../examples/so-probe/README.md)。

## 下载文件与校验

从 [Releases](https://github.com/EMERLADD/proot-distro-nolib/releases) 取得同版本附件：

| 文件 | 内容 |
| --- | --- |
| `pdn / proot-loader` | 原始 ARM64 ELF |
| `libpdn.so / libproot-loader.so` | 供 APK 原生库目录打包的 ELF 可执行程序 |
| `pdn-engine-VERSION.aar` | Java/Kotlin API、PDN、loader 与 PTY JNI |
| `pdn-engine-lite-VERSION.aar` | 与标准 AAR 字节相同的兼容文件名 |
| `proot-distro-nolib-vVERSION-android-arm64.tar.gz` | 原生程序、对应源码、依赖源码、说明和许可证 |
| `pdn-*-probe-VERSION-*-test.apk` | AAR / .so 的 Debug 与 R8 Release 普通测试 App，使用测试签名 |
| `SHA256SUMS` | 附件 SHA256 校验值 |

将同版清单包含的附件放在同一目录后校验：

```sh
sha256sum -c SHA256SUMS
```

不同构建的文件应使用各自的校验清单，不混用。更新时同时替换 PDN 与 loader，退出旧会话后再启动；已有 rootfs 不需要重装。

## 对应源码与许可证

完整包包含 `BUILD-INFO.txt`、`LICENSE`、`licenses/` 和 `source.tar.gz`。源码包带有 talloc 源码以及 Mbed TLS、curl、libarchive、zlib 的原始源码归档，但不包含 Android SDK/NDK。

自行分发二进制时须同时提供对应源码和第三方许可证。引擎沿用 GPL-2.0-or-later，talloc 为 LGPL-3.0-or-later，其他组件按各自许可证分发；项目不能统一声明为 MIT。具体范围见 [LICENSE](../LICENSE)。
