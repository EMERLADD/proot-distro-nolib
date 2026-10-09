# PDN AAR 原生组件精简

目的：所有 AAR 仅包含 PDN、loader、PTY JNI，保留全部终端 API。lite 文件名保留为字节相同别名。原 App 单独打包旧 pr 组件。本轮不构建 APK。

- [x] 将旧 pr 原生组件从 engine AAR 移至 App 自行打包，覆盖标准与 Termux 路径。
- [x] 发布过滤全部 AAR，保留 API 与元数据，别名复制相同字节。
- [x] 版本、User-Agent、当前文档与测试对齐 0.6.6，保留历史验收记录。
- [x] 验证打包测试与覆盖率、实际 AAR 原生文件列表与终端类、SDK 单元测试覆盖率。
- [x] 验证 App 旧组件 staging（不构建 APK），交由主代理检查并提交。

验证：打包回归 9/9，打包脚本行覆盖率 51/55（92.73%）；SDK 单元测试 90/90，行覆盖率 893/1044（85.54%）；原生回归 16/16。实际 Debug AAR 仅含 PDN、loader、PTY JNI，保留全部 PdnTerminal 类；两个发布名字节相同。Termux App staging 验证保留原 App 的旧组件，标准 engine staging 只提供 PDN 与 loader，PTY JNI 由 CMake 构建。标准 App staging 在配置 vendor termlib 时被本机缺少其首选 NDK 27.0.12077973 阻止，未执行该任务；标准分支的源路径及过滤规则经过代码审查，未修改 vendor 或扩展本轮环境配置。不构建 APK，不将 0.6.5 实机结果冒作 0.6.6 验证。
