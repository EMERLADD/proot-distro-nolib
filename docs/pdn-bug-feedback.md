# PDN 问题反馈

## Claude 子进程的 LD_PRELOAD 包含 Termux 路径

- 反馈日期：2026-10-10。
- 状态：后续排查已定位来源，关闭此项 PDN 缺陷记录。
- 初次反馈仅记录；获得后续排查授权后，检查了 rish 启动的 Ubuntu 与 Claude 子进程环境。
- 结果：宿主与 guest 的 LD_PRELOAD 为空；Claude 配置的 `env.LD_PRELOAD` 显式注入了 Termux 库路径，导致其 Bash 工具及 Android shell 加载失败。
- 此次现象来自 Claude 配置，未发现 PDN 登录环境泄漏。没有修改 Claude 配置，也没有为此改动 PDN。
- `rish: command not found` 还需要在实际执行命令的 shell 中提供命令或脚本；交互 shell 中定义的函数不会自动成为其他进程可用的命令。
