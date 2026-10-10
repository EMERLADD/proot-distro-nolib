# Start Ubuntu from an Android shell and call Android commands

[简体中文](pdn-shizuku-android-shell.md) | English | [Back to README](../README.en.md)

This applies to Android shells that can run PDN and access the chosen directories, without requiring a particular terminal app. The examples use MT Manager and Shizuku/rish; an existing ADB shell can start at PDN deployment. All permissions still depend on the real Android identity starting PDN and on system restrictions.

Calling Android commands and reading/writing system settings from Ubuntu were verified and reproduced in MT Manager. See the [test records](pdn-error-testing.en.md#ubuntu-android-commands) for versions, environments and detailed results.

## Contents

- [Three terminal environments](#three-terminal-environments)
- [Foreground and connection requirements](#foreground-and-connection-requirements)
- [Enter an Android shell](#enter-an-android-shell)
- [Deploy PDN and install Ubuntu](#deploy-pdn-and-install-ubuntu)
- [Enter Ubuntu with mounts](#enter-ubuntu-with-mounts)
- [Give the long command the name rish](#give-the-long-command-the-name-rish)
- [Verify Android settings and clean up](#verify-android-settings-and-clean-up)
- [Next login and environment removal](#next-login-and-environment-removal)
- [Troubleshooting](#troubleshooting)

## Three terminal environments

```text
The host app's original terminal
  → Original rish client connects to Shizuku
  → Android shell, usually real UID 2000
  → PDN starts Ubuntu
  → Android shell and system commands are called inside Ubuntu
```

| Environment | What to do here |
| --- | --- |
| MT's original terminal | Set up the original rish client initially; access MT's own private directory |
| Shizuku/ADB Android shell | Deploy PDN, configure directories, install and start Ubuntu |
| Ubuntu's Bash | Define a command name, run Linux programs, or call Android commands |

The root shown in Ubuntu's prompt is a PRoot-simulated identity. Underlying processes inherit the Android permissions held when PDN starts. Shizuku in wireless debugging mode usually provides shell privileges, not real Android root.

The `rish` defined later inside Ubuntu is a Bash function calling `/system/bin/sh` with inherited permissions. It does not reconnect to Shizuku. Nested startup of the original rish client in this shell still timed out during testing, so this tutorial uses the function wrapper.

## Foreground and connection requirements

Keep the Shizuku-authorized host app that starts the original rish client in the foreground throughout use. In the tested environment, backgrounding the host disconnected it; even the “Unrestricted” battery policy did not guarantee a persistent connection. Other systems may behave differently. Follow this requirement when reproducing the test.

After restarting Shizuku, start the original rish client again from the host terminal, then enter Ubuntu. You do not need to reinstall Ubuntu.

Code blocks do not include prompts. Do not copy an extra `$` or `root@localhost:~#`. On the first run, execute each step separately rather than pasting commands for different environments together.

## Enter an Android shell

**If `rish` already works in the host terminal, run it and then `id`; skip the initial setup below.**

```sh
rish
id
```

Wireless debugging mode should show `uid=2000(shell)`, usually with `u:r:shell:s0`. If you already have that identity, do not access MT's private directory to configure rish again; continue to the next section. ADB shells can also start at the next section.

### If the original rish client is not configured yet

Export `rish` and `rish_shizuku.dex` from Shizuku's “Use Shizuku in terminal apps” entry. For example, save them manually at:

```text
/sdcard/yyd/rish-source/rish
/sdcard/yyd/rish-source/rish_shizuku.dex
```

You must export and prepare these files yourself; the directory is not created automatically. Starting with Android 14, dex files loaded by app_process must not be writable, so copy them into the host's private directory and then set permissions.

This example uses MT's package name `bin.mt.plus`. Other versions or apps must use their actual package name and an accessible private directory. Run this in **MT's original terminal, before entering rish**:

```sh
MT_RISH_DIR=/data/user/0/bin.mt.plus/files/pdn-rish
mkdir -p "$MT_RISH_DIR"
cp /sdcard/yyd/rish-source/rish "$MT_RISH_DIR/rish"
cp /sdcard/yyd/rish-source/rish_shizuku.dex "$MT_RISH_DIR/rish_shizuku.dex"
chmod 700 "$MT_RISH_DIR"
chmod 700 "$MT_RISH_DIR/rish"
chmod 400 "$MT_RISH_DIR/rish_shizuku.dex"
export RISH_APPLICATION_ID=bin.mt.plus
sh "$MT_RISH_DIR/rish"
```

If the exported script requires changing its `PKG`, enter the actual host application ID according to Shizuku's export instructions. Confirm that Shizuku has authorized the app, then run `id` after entering to check the real identity.

This initial setup was not part of the MT reproduction acceptance test; MT reproduction started with an already working rish. If Android shell cannot access MT's private directory, return to MT's original terminal to configure it.

## Deploy PDN and install Ubuntu

Run the following in the **Android shell**. Download `pdn` and `proot-loader` from the same Release. These examples assume they are in `/sdcard/yyd/PDN/`; change the source paths in the two `cp` commands if they are elsewhere.

```sh
mkdir -p /data/local/tmp/pdn-mt
cp /sdcard/yyd/PDN/pdn /data/local/tmp/pdn-mt/pdn
cp /sdcard/yyd/PDN/proot-loader /data/local/tmp/pdn-mt/proot-loader
chmod 755 /data/local/tmp/pdn-mt/pdn
chmod 755 /data/local/tmp/pdn-mt/proot-loader
cd /data/local/tmp/pdn-mt
./pdn version
./pdn --version
```

Both version commands should show the current PDN version, 0.6.6 in this test. `Based on PRoot 5.4.0-pr` identifies the underlying version.

```sh
export PDN_ROOTFS_DIR=/data/local/tmp/pdn-mt/linux
export PROOT_TMP_DIR=/data/local/tmp/pdn-mt/tmp
export PROOT_LOADER=/data/local/tmp/pdn-mt/proot-loader
mkdir -p "$PROOT_TMP_DIR"
./pdn install ubuntu
```

Wait for download, SHA256 verification, and extraction to finish. Do not create `linux/ubuntu` beforehand. If already installed, skip `install` and log in directly.

`/data/local/tmp/pdn-mt` is an example writable shell directory, not a fixed requirement. If choosing another directory, update the program, rootfs, temporary directory, and loader paths together. Do not extract the rootfs into `/sdcard`.

## Enter Ubuntu with mounts

Run this entire line in the **Android shell**:

```sh
./pdn login ubuntu --bind /system --bind /apex --bind /linkerconfig/ld.config.txt
```

After entering Ubuntu, check:

```sh
head -n 2 /etc/os-release
grep '^Uid:' /proc/self/status
```

The test used Ubuntu 24.04.5 LTS with actual UID 2000; the guest's `id` reports simulated root.

These three mounts provide Android system programs, APEX runtime libraries, and dynamic linker configuration. They apply only to this login and must be supplied again next time. Listing `/linkerconfig` may be disallowed while the specific configuration file remains accessible; this test mounted the file directly.

These mounts were sufficient for the tested commands. Other programs or systems may need additional Android paths.

## Give the long command the name rish

Run the entire block **inside Ubuntu**. It uses `--` as the child shell's name argument to avoid accidentally joining a closing quote to a name:

```sh
rish() {
    /system/bin/sh -c 'export PATH=/system/bin:/system/xbin; exec /system/bin/sh "$@"' -- "$@"
}
```

Test first:

```sh
type rish
rish -c 'getprop ro.build.version.sdk'
rish -c 'cmd package path android'
```

`type rish` should show `rish is a function`. In this test, the Android commands printed SDK `34` and `package:/system/framework/framework-res.apk`. The SDK number depends on your system.

After it works, save it inside Ubuntu. **Append it only once**:

```sh
cat >> ~/.bashrc <<'EOF'
rish() {
    /system/bin/sh -c 'export PATH=/system/bin:/system/xbin; exec /system/bin/sh "$@"' -- "$@"
}
EOF
source ~/.bashrc
```

Bash will load the function on later interactive Ubuntu logins. To name it `ash`, replace `rish()` with `ash()` and call `ash -c 'COMMAND'`. Keep `"$@"` unchanged; it preserves argument boundaries.

Examples:

```sh
rish -c 'getprop ro.build.version.sdk; settings get global airplane_mode_on'
rish
```

Running `rish` alone enters an interactive Android shell. Run `exit` to return to Ubuntu, then another `exit` to leave Ubuntu. The function is available only in Bash instances that load it; it is not an executable file that arbitrary programs can launch directly.

This sets PATH separately for the Android child shell. Ubuntu's own PATH stays unchanged, so Linux commands such as `apt` continue to work normally.

## Verify Android settings and clean up

First check the dedicated test key **inside Ubuntu**:

```sh
rish -c 'settings get global pdn_ubuntu_demo_20261009'
```

`null` means it does not exist yet and is normal. If it already has a value, choose an unused name beginning with `pdn_ubuntu_demo_` and use that name consistently in every command.

```sh
rish -c 'settings put global pdn_ubuntu_demo_20261009 hello-from-ubuntu'
rish -c 'settings get global pdn_ubuntu_demo_20261009'
exit
```

Back in the **outer Android shell**, verify and delete it:

```sh
settings get global pdn_ubuntu_demo_20261009
settings delete global pdn_ubuntu_demo_20261009
settings get global pdn_ubuntu_demo_20261009
```

The first command should print `hello-from-ubuntu`; after cleanup it should print `null`. This is Android's real settings database, not an Ubuntu configuration file. The dedicated test key does not toggle existing system features such as airplane mode.

If disconnected midway, delete the test key after reconnecting to the Android shell. This verification was successfully reproduced in MT Manager.

## Next login and environment removal

Reconnect the original rish client from the host terminal. Once in the **Android shell**:

```sh
cd /data/local/tmp/pdn-mt
export PDN_ROOTFS_DIR=/data/local/tmp/pdn-mt/linux
export PROOT_TMP_DIR=/data/local/tmp/pdn-mt/tmp
export PROOT_LOADER=/data/local/tmp/pdn-mt/proot-loader
mkdir -p "$PROOT_TMP_DIR"
./pdn login ubuntu --bind /system --bind /apex --bind /linkerconfig/ld.config.txt
```

The function saved inside Ubuntu loads automatically. Neither reinstalling Ubuntu nor copying the original rish and dex into Ubuntu is necessary.

After testing, exit the Android child shell and Ubuntu, confirm there is no data to retain, then delete the entire directory created by this tutorial from the **outer Android shell**:

```sh
rm -rf /data/local/tmp/pdn-mt
```

This deletes Ubuntu, the PDN copies, and temporary files in that directory, without deleting release artifacts in the download directory. Keep the original rish client in the host's private directory if you still need it.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| `Request timeout` or sudden disconnection | Keep the host in the foreground, check Shizuku and authorization, and reconnect after service restarts. |
| `Permission denied` in MT's private directory | Initial setup of the original rish client belongs in MT's original terminal. Skip it if rish already works. |
| Missing `rish-source` | This is an example manual export directory. Export the files first or change the paths. |
| `cannot access rootfs directory` | Set `PDN_ROOTFS_DIR` again; do not keep the default path derived from `HOME=/`. |
| `temporary directory unavailable` | Check `PROOT_TMP_DIR`, create it in the Android shell, and check permissions. |
| `rootfs already exists` | Ubuntu is already installed; log in directly. |
| `bash: rish: command not found` | Define the function inside Ubuntu, test it in the current session, then save it in `.bashrc`. |
| Prompt remains `>` | A quote, function brace, or heredoc is unfinished. Press Ctrl+C and paste the complete code block again. |
| Unexpected `android` appended to a command | The old function joined a closing quote to its name argument. Redefine it using the `--` version above. |
| `/usr/bin/rish: required file not found` | The original script's interpreter is missing. Log in with the mounts shown above and use the function wrapper. |
| Missing Android dynamic libraries or linker configuration warnings | Check APEX and linker configuration file mounts. Other programs may need more system paths. |
| Original rish incorrectly reports writable dex inside Ubuntu | PRoot's simulated root affects the check. Configure the client on the host; use the inherited-permission function inside Ubuntu. |
| Android command lacks permission | Check the real Android identity before starting PDN. Simulated root adds no host permissions; shell itself also has permission limits. |
