# PDN 错误说明

简体中文 | [English](pdn-errors.en.md) · [返回 README](../README.md)

失败时先查看 `message`、`suggestion` 和 stderr；`code` 用于程序分类，`outcome` 表示最终结果。Linux 命令非零退出属于 `guest_exit`，不会按退出码猜测为安装器错误。

| code | 含义与处理 |
| --- | --- |
| `invalid_argument` | 参数无效；检查命令、路径与选项格式。 |
| `rootfs_missing` / `directory_missing` / `file_missing` | 目标不存在；检查选择的实例和实际路径。 |
| `directory_not_directory` | 需要目录的位置被普通文件占用。 |
| `directory_permission` / `file_permission` | 宿主权限不足；使用当前身份可访问的目录。 |
| `directory_read_only` / `file_read_only` | 所在文件系统只读；选择可写位置。 |
| `directory_unavailable` / `file_io_failed` | 目录或文件操作失败；根据 message 和原始系统原因排查。 |
| `bind_source_missing` / `bind_source_unavailable` | 挂载来源不存在、不可访问或类型不支持。 |
| `name_ambiguous` | 有多个仅大小写不同的名称；使用明确的 rootfs 路径。 |
| `rootfs_exists` / `file_exists` | 目标已存在；选用新名称，不会覆盖原内容。 |
| `operation_busy` | 安装锁或实例被占用；退出相关会话，等待当前操作结束。 |
| `lock_failed` | 无法建立操作锁；检查锁路径权限和文件类型。 |
| `distro_unknown` / `mirror_invalid` | 发行版或来源名称无效；用 list --available 和 mirrors 查询。 |
| `resolution_failed` / `connection_failed` / `download_timeout` | 域名解析、连接或传输超时；检查网络与代理，必要时选择其他来源。 |
| `tls_failed` | TLS 校验失败；检查系统时间、信任证书和代理，不要关闭证书校验。 |
| `http_error` / `download_failed` | 服务器或传输失败；查看 HTTP 状态与具体原因，稍后重试或换来源。 |
| `storage_full` | 存储空间或配额不足；清理空间后重试。 |
| `out_of_memory` | 内存不足；减少同时运行的任务后重试。 |
| `archive_size_mismatch` / `archive_checksum_mismatch` | 归档大小或 SHA256 不匹配；重新取得指定版本的完整归档。 |
| `archive_hash_failed` | 无法完成摘要校验；检查文件读取和实际错误原因。 |
| `archive_corrupt` / `archive_invalid` | 输入不是有效 rootfs 归档，或归档已损坏。 |
| `archive_unsafe` / `archive_unsupported` / `archive_limit_exceeded` | 归档路径、条目类型或大小不符合安全限制；不要绕过限制。 |
| `extraction_failed` / `rootfs_configuration_failed` | 解压或发行版配置失败；查看具体文件与原因后重试。 |
| `keyring_initialization_failed` | Arch 密钥初始化失败；检查网络、临时目录和子进程输出。 |
| `publish_failed` | 无法完成安装或文件发布；检查目标冲突、权限与存储。 |
| `config_invalid` / `config_unsafe` | 保存的配置无效或不安全；可用 --no-config 进入后重新配置。 |
| `config_read_failed` / `config_write_failed` | 配置读写失败；检查权限及具体系统原因。 |
| `user_invalid` | 账号或 UID/GID 无效；检查 guest 的 passwd 与账号参数。 |
| `uninstall_failed` / `uninstall_incomplete` | 删除失败或仅部分删除；解决具体原因后再次卸载，没有撤销功能。 |
| `cache_limit_exceeded` | 缓存条目数量或大小超出查询限制；检查目录，分批清理或移出无关文件后重试。 |
| `cache_unsafe` | 缓存目录或文件类型不安全；检查所报路径，不要绕过拒绝或递归删除。 |
| `rollback_failed` | 恢复旧状态失败；保留恢复文件，按 suggestion 检查，不要直接删除。 |
| `manager_failed` | 管理操作失败的兜底分类；结合 stderr 和 message 排查。 |
| `host_cache_failed` / `host_event_channel_failed` | 宿主缓存或事件通道不可用；检查 App 私有目录。 |
| `host_process_start_failed` | 无法启动原生程序；检查 nativeLibraryDir、原生库解压和 loader。 |
| `host_output_failed` / `host_cleanup_failed` | 输出读取或资源清理失败；检查原始 cause 与 suppressed 异常。 |
| `host_protocol_error` / `host_catalog_protocol` | 事件或查询格式无效、不完整；确认 API 与原生程序版本匹配。 |

PRoot、loader、guest shell 和 ELF 动态加载器的启动分类见[事件协议](pdn-events.md#proot-与-guest-启动错误)。兼容旧版本或无法进一步细分时可能返回其他兜底分类，调用方应保留未知 code 的原始说明。

Java/Kotlin 调用需要检查 `PdnResult.isSuccess()`。宿主错误抛出继承 `IOException` 的 `PdnHostException`，通过 `getCode()`、`getSuggestion()` 和 `getCause()` 排查；参数错误与线程中断保持原有异常。错误处理接口见 [AAR API](pdn-aar-api.md)。

`host_document_io_failed`: 所选文档或流读写失败；检查 URI 授权、提供者状态和存储空间，导出失败后移除不完整文档再重试。
