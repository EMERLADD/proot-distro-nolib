# 构建与发布

简体中文 | [English](pdn-build-and-release.en.md) · [返回 README](../README.md)

## 目录

- [本地构建 ELF](#本地构建-elf)
- [测试与打包](#测试与打包)
- [构建 AAR](#构建-aar)
- [GitHub 自动构建](#github-自动构建)
- [发布材料与对应源码](#发布材料与对应源码)
- [来源与许可证](#来源与许可证)

## 本地构建 ELF

独立 PDN 构建不需要 Java、Gradle 或 Rust。需要 Git、Make、Clang/LLVM 工具、curl、tar、xz/bzip2、pkg-config 和 Android NDK。第一次构建需要联网下载带固定校验值的依赖源码。

```sh
git clone https://github.com/EMERLADD/proot-distro-nolib.git
cd proot-distro-nolib
git submodule update --init --depth 1 vendor/samba
make NDK_PATH=/你的/Android/NDK/目录
```

在 Linux x86_64 主机上可以使用 NDK 自带 LLVM 工具：

```sh
export NDK_PATH="$HOME/Android/Sdk/ndk/26.3.11579264"
export PATH="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
make NDK_PATH="$NDK_PATH"
```

ARM64 Android 本机编译需要能在 Android 上运行的 Clang/LLVM 工具，再使用 NDK sysroot，不能直接运行 Linux x86_64 编译器。构建工具可以来自 Termux，产物运行不依赖 Termux。

输出位于 `build/proot-distro-nolib/arm64/`。构建检查 ELF 动态依赖、RPATH 和残留宿主路径，不安装 App，也不改写宿主 bin。

## 测试与打包

```sh
make help
make test
make package
make clean
```

`make test` 需在允许 PRoot 运行的 ARM64 Android 环境执行，还需要 Python 3 和仓库测试使用的 BusyBox fixture。Linux x86_64 上的交叉编译成功不是 Android 运行测试成功。具体回归与覆盖率见 [测试记录](pdn-error-testing.md) 和 [更新记录](../CHANGELOG.md)。

`make package` 先编译，再打包已提交源码和产物。打包前提交项目文件，确保源码对应当前提交；输出位于 `build/packages/`。更换编译器、NDK 或依赖编译参数时先 `make clean`，避免复用旧静态库。

## 构建 AAR

先构建原生 PDN 与 loader，再在 `android/` 中执行引擎构建：

```sh
cd android
sh gradlew -PpdnEngineOnly=true :proot-engine:bundleDebugAar
```

此流程还需要 Gradle、Java、Android SDK 和 PTY JNI 构建环境；不需要配置原 App 的 GUI 和终端库模块。目录准备及本机打包步骤见 [App 接入教程](android-embedding.md)、[AAR 验证工程](../examples/aar-probe/README.md) 和 [AAR 接口](pdn-aar-api.md)。

0.6.6 起两个 AAR 命名产物都只带 PDN、loader、PTY JNI，保留终端功能，不包含旧 pr 的原生组件。`libpdn.so` 和 `libproot-loader.so` 是改名后的 ELF 可执行程序；PTY JNI 才是由 JVM 加载的 JNI 库。AAR 需配合 Kotlin 标准库使用，当前发布的是非插桩 Debug 引擎构建。现有 AAR 已在开启 R8 的非 debuggable Release 测试 App 中验收通过，使用 Android 默认优化/JNI 规则及本地测试签名；直接 .so 接入同样通过。详见 [混淆验收](pdn-error-testing.md#066-releaser8-混淆验收)。Maven 发布尚未实现。

## GitHub 自动构建

工作流：[`.github/workflows/ci.yml`](../.github/workflows/ci.yml)。`main` 推送、`v*` 标签推送、Pull Request 和手动 Run workflow 都可触发。

流程使用 Ubuntu 24.04 和固定 NDK `26.3.11579264`：

1. 获取仓库及 talloc 子模块源码。
2. 下载、校验并静态编译固定来源的依赖。
3. 编译 ARM64 PDN 与 loader，检查动态依赖和宿主路径。
4. 打包二进制、许可文本、使用材料与对应源码，生成 SHA256。
5. 单独构建引擎 AAR，执行 JVM 测试和覆盖率检查，核对 AAR 内 PDN/loader 与原始 ELF 的字节一致性，更新附件 SHA256。
6. 上传 Actions artifact，保留 30 天。
7. `main` 推送包含尚未发布的版本号时，自动创建对应版本的草稿预发布 Release；同版本 Release 或草稿已存在则跳过。版本标签推送也可创建草稿，但标签需与代码版本一致。

Pull Request 和手动构建仅生成附件，草稿创建仅在 `main` 或版本标签推送后进行；公开发布需等待实机验收。Linux runner 的交叉编译与产物检查不能替代 Android 实机运行验收。

开发构建可从 [Actions](https://github.com/EMERLADD/proot-distro-nolib/actions/workflows/ci.yml) 的成功运行中下载 `pdn-android-arm64-提交号`。下载通常需要登录 GitHub，且有保留期限，不等于长期 Release。

原 pr App 的 [Legacy pr Android App 工作流](../.github/workflows/legacy-pr.yml) 仅手动触发，不属于独立 PDN 的默认流程。

## 发布材料与对应源码

完整包名为 `proot-distro-nolib-v0.6.6-android-arm64.tar.gz`。解压后包含：

| 文件 | 内容 |
| --- | --- |
| `pdn`、`proot-distro-nolib`、`proot-loader` | ARM64 可执行程序及可选 loader；前两个内容相同 |
| `jniLibs/arm64-v8a/` | 与原始 ELF 相同的 `libpdn.so`、`libproot-loader.so` |
| `SHA256SUMS` | 包内程序及两个 APK 原生文件的校验值 |
| `BUILD-INFO.txt` | 项目版本、源码提交号、talloc 来源提交号 |
| `README.md`、`README.en.md`、`CHANGELOG.md`、`docs/` | 项目介绍、更新记录和详细文档 |
| `LICENSE`、`licenses/` | 许可证映射及第三方许可文本 |
| `source.tar.gz` | 对应仓库源码、构建所需 talloc 源码、四个依赖的原始源码归档 |

Release 附件另提供 `pdn-engine-0.6.6.aar`、`pdn-engine-lite-0.6.6.aar`、`pdn`、`proot-loader`、`libpdn.so`、`libproot-loader.so` 和完整包。外部 `SHA256SUMS` 校验两个 AAR、原始 ELF、两个 `.so` 及完整 `.tar.gz`：

```sh
sha256sum -c SHA256SUMS
```

执行前把同一版清单涉及的全部附件下载到同一目录。不同构建环境的本地产物可能与 CI 产物字节不同，应使用各自对应的校验清单，不混用文件。

源码包解压后可用上述 NDK 工具链运行 `make`；Mbed TLS、curl、libarchive、zlib 四个依赖归档已包含，构建仍校验其内容。包内不包含 Android SDK/NDK 本身。

交付时同步同版本 ELF、`.so`、AAR 和 loader，并更新无版本文件名入口。验证 `pdn version`、`pdn --version` 都显示当前 PDN 版本；PRoot 基底版本单独维护。转发二进制需一起保留对应源码及许可材料。

## 来源与许可证

- [oonid/pr](https://github.com/oonid/pr)：项目基底，提供 Android PRoot 适配及原 App/CLI。
- [PRoot](https://github.com/proot-me/proot)、[Termux PRoot](https://github.com/termux/proot)：引擎和 Android 相关改动，沿用 GPL-2.0-or-later 声明。
- [Termux proot-distro](https://github.com/termux/proot-distro)：使用方式及管理思路参考，不是运行依赖。
- [talloc / Samba](https://www.samba.org/)、[curl](https://curl.se/)、[Mbed TLS](https://github.com/Mbed-TLS/mbedtls)、[libarchive](https://www.libarchive.org/)、[zlib](https://zlib.net/)：使用的构建组件；talloc 源文件声明 LGPL-3.0-or-later，其余许可附在发布包中。

项目不统一宣称为 MIT；许可证范围见 [LICENSE](../LICENSE) 和源文件声明。`nolib` 说明运行不依赖 Termux 动态库，不抹去上游来源、版权或贡献。

## 发布前实机验收

CI 只创建草稿 Release。公开发布前，使用候选附件原件完成四种独立 App 验收：AAR Debug、AAR Release/R8、直接 `.so` Debug、直接 `.so` Release/R8。核对实际类名混淆、签名、Manifest、APK 内原生文件字节，并在 Android 验证初始化、发行版安装、命令、事件和 PTY。四种全部通过后才公开草稿并上传测试 APK。Release 测试 APK 使用 Debug 测试密钥，不是生产签名。Linux CI 的编译成功不替代这一步。
