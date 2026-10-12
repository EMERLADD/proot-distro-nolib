# PDN error reference

[简体中文](pdn-errors.md) | English · [README](../README.en.md)

On failure, inspect `message`, `suggestion` and stderr. `code` classifies the cause and `outcome` describes the final result. A nonzero Linux command exit remains `guest_exit`; it is not classified as an installer error based on its exit number.

| code | Meaning and recovery |
| --- | --- |
| `invalid_argument` | Invalid argument; check the command, paths and option syntax. |
| `rootfs_missing` / `directory_missing` / `file_missing` | The target is missing; check the selected instance and actual path. |
| `directory_not_directory` | A required directory path refers to a file. |
| `directory_permission` / `file_permission` | Host access is denied; use paths accessible to the current identity. |
| `directory_read_only` / `file_read_only` | The filesystem is read-only; select a writable location. |
| `directory_unavailable` / `file_io_failed` | A directory or file operation failed; inspect the message and original system cause. |
| `bind_source_missing` / `bind_source_unavailable` | A bind source is missing, inaccessible or unsupported. |
| `name_ambiguous` | Names differ only in case; select an explicit rootfs path. |
| `rootfs_exists` / `file_exists` | The destination exists; choose a new name. Existing content is not overwritten. |
| `operation_busy` | A management lock or instance is busy; close relevant sessions and wait for the active operation. |
| `lock_failed` | The operation lock could not be acquired; check path permissions and file type. |
| `distro_unknown` / `mirror_invalid` | The distribution or mirror is unknown; query list --available and mirrors. |
| `resolution_failed` / `connection_failed` / `download_timeout` | DNS, connection or transfer timeout; check networking and proxy settings or choose another mirror. |
| `tls_failed` | TLS verification failed; check the clock, trusted certificates and proxy without disabling verification. |
| `http_error` / `download_failed` | Server or transfer failure; inspect the HTTP status and cause, then retry or select another mirror. |
| `storage_full` | Storage or quota is exhausted; free space and retry. |
| `out_of_memory` | Insufficient memory; reduce concurrent tasks and retry. |
| `archive_size_mismatch` / `archive_checksum_mismatch` | Archive size or SHA256 mismatch; obtain the complete pinned archive again. |
| `archive_hash_failed` | Hash verification could not finish; inspect file access and the actual error. |
| `archive_corrupt` / `archive_invalid` | The input is not a valid rootfs archive or is damaged. |
| `archive_unsafe` / `archive_unsupported` / `archive_limit_exceeded` | Archive paths, entry types or sizes violate safety limits; do not bypass them. |
| `extraction_failed` / `rootfs_configuration_failed` | Extraction or distribution configuration failed; inspect the affected file and cause before retrying. |
| `keyring_initialization_failed` | Arch keyring initialization failed; check networking, the temporary directory and subprocess output. |
| `publish_failed` | Installation or file publication failed; check conflicts, permissions and storage. |
| `config_invalid` / `config_unsafe` | Saved configuration is invalid or unsafe; use --no-config to enter and replace it. |
| `config_read_failed` / `config_write_failed` | Configuration I/O failed; inspect permissions and the system cause. |
| `user_invalid` | Invalid account or UID/GID; check guest passwd and account arguments. |
| `uninstall_failed` / `uninstall_incomplete` | Removal failed or was partial; resolve the cause and repeat removal. There is no undo. |
| `cache_limit_exceeded` | Cache entry count or size exceeds limits; inspect the directory, clear selected distributions or move unrelated files and retry. |
| `cache_unsafe` | Unsafe cache directory or file; inspect the reported path without bypassing the refusal or recursively deleting it. |
| `rollback_failed` | Rollback failed; retain recovery files and follow the suggestion rather than deleting them. |
| `manager_failed` | Fallback management failure; inspect stderr and message. |
| `host_cache_failed` / `host_event_channel_failed` | Host cache or event channel unavailable; check the App private directory. |
| `host_process_start_failed` | Native process startup failed; check nativeLibraryDir, native-library extraction and loader. |
| `host_output_failed` / `host_cleanup_failed` | Output reading or cleanup failed; inspect the original cause and suppressed exceptions. |
| `host_protocol_error` / `host_catalog_protocol` | Invalid or incomplete event/query format; check API and native program versions. |

See the [event protocol](pdn-events.en.md#proot-and-guest-startup-errors) for PRoot, loader, guest-shell and ELF-interpreter startup classifications. Compatibility and unspecialized failures may use other fallback codes; retain the original explanation for unknown codes.

Java/Kotlin callers must check `PdnResult.isSuccess()`. Host failures throw `PdnHostException`, which extends `IOException`; inspect `getCode()`, `getSuggestion()` and `getCause()`. Argument errors and thread interruption retain their existing exceptions. See the [AAR API](pdn-aar-api.en.md).

`host_document_io_failed`: Selected document/stream I/O failed. Check URI access, provider availability and storage; remove incomplete exports before retrying.

Interrupted installation errors:

- `recovery_unsafe`：Staging ownership, identity, tree or mount safety is unproven; retain and inspect.
- `recovery_io_failed`：Recovery record, phase persistence, inspection or cleanup I/O failed; check permissions/storage. Cleanup may be partial.
- `recovery_not_found`：Selected recovery ID is missing; refresh the list.
- `recovery_limit_exceeded`：Too many management directory entries; reduce unrelated files and retry.
