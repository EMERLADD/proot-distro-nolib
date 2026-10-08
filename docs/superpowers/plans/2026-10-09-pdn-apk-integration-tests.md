# PDN 0.6.4 APK 接入补测

目的：分别验证外部 AAR 接入和直接打包 Release `.so` 的 Android App。两种方式都必须在 targetSdk 35 的普通 App 进程中运行，不能用 rish 或 run-as 的权限代替 App 运行权限。

- [x] 更新独立 AAR 验证工程，固定 GitHub Release 0.6.4 AAR；增加启动错误、guest 退出和事件回调检查。
- [x] 新建不依赖 AAR 或原仓库源码模块的 `.so` 验证工程；使用 nativeLibraryDir 中的 Release ELF、宿主私有目录及 JSONL 协议。
- [x] 构建两份 APK，检查签名、Manifest、原生库字节一致性和运行依赖。
- [x] 通过已授权的 rish 安装并启动 App instrumentation；验证初始化、安装、执行、交互登录、事件及可在 App 环境触发的启动错误。
- [x] 收集实际验收报告与覆盖率，新增或修改验证逻辑达到 80%；失败则修复并重新验证。
- [x] 完成需求符合性与代码质量审查，保存结果、接入说明和 APK，提交并推送。

范围：测试工程与文档。PDN 核心维持 0.6.4；仅当发现必须修复的核心错误时另行更新版本。受 SELinux 限制的错误依据实际 errno 断言，不把 App 数据目录中的 loader 格式错误冒充 nativeLibraryDir 的格式检查。测试 rootfs 使用独立目录，不能破坏已有 App 的 Alpine。

## 验收结果

AAR 19/19、直接 `.so` 24/24，在 Android 14 的普通 App 进程中通过。Java 行覆盖率分别为 93.61%、95.95%，自有 PTY JNI 为 89.32%。需求符合性与代码质量审查完成；原生文件保持 GitHub Release 0.6.4 原件，PDN 核心未修改。修正了测试 asset 名称、guest 符号链接检查、失败报告保留和自有 JNI/事件读取器验证。交付记录见 `docs/pdn-error-testing.md`。
