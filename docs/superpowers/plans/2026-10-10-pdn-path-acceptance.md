# PDN 路径验收与启动耗时对比

范围：原始 ELF 的 Android shell 验收，以及仅依赖 AAR、直接打包 `.so` 的两个独立 App。保护现有发行版，使用独立测试目录；不把路径映射正确性解释为安全沙箱隔离。

- [x] 增加 14 项路径用例：guest `/usr` 映射、嵌套 bind 双顺序、组件边界、绝对/相对链接、跨 rootfs 链接、断链创建、循环链接和缺失目标。
- [x] 独立规格审查与质量审查；加入正向读取预检防止负向用例误通过。
- [x] 本地同版本构建通过 rish 14/14、AAR 47/47、`.so` 38/38；新增 Java 路径测试行覆盖率 96.33%。
- [x] 使用 GitHub Release 0.6.6 原件复验三条路径，核对签名、Manifest 与包内 ELF 字节。
- [x] 同一 Alpine ARM64 归档对比 Termux proot-distro 与 Android shell PDN 的启动耗时，不计 rish 连接、下载、安装及 GUI 渲染。
- [ ] 发布两个非插桩 Debug 测试 APK，明确用途和身份，补齐记录、校验值及源码，提交并推送。

Release 原件与非插桩测试 APK 均通过：rish 14/14、AAR 47/47、直接 `.so` 38/38。新路径类行覆盖率 105/109（96.33%）。两次顺序对照中位数：Termux pd 272.2 ms，Android shell PDN 40.9 ms；清理环境后 pd 268.6 ms，PDN 35.5 ms。
