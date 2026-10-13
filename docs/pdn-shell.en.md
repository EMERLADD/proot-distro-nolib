# Deploy the PDN ELF in MT Manager's terminal

[简体中文](pdn-shell.md) | English · [README](../README.en.md)

This guide uses **MT Manager's ordinary built-in terminal** to place the original ELF in App-private storage, configure Linux storage and register the `pdn` command. Installing and running Linux does not require entering rish or installing Termux. Other Android terminals permitted to execute ELF files can follow the same steps using their own accessible directories.

To call system debugging commands with Android shell privileges from Linux, see [debug Android](pdn-shizuku-android-shell.en.md).

## 1. Prepare the files and terminal

Download matching ARM64 Android `pdn` and `proot-loader` files from [Releases](https://github.com/EMERLADD/proot-distro-nolib/releases) and verify `SHA256SUMS`. Downloads can go in `/sdcard/Download/`; deploy the programs and rootfs in private storage.

Open MT Manager's built-in terminal and check your environment:

```sh
id
pwd
```

Run the following steps in **MT's original terminal**. The example uses private storage for package `bin.mt.plus`; substitute your actual package and terminal directory if different. If you have already entered rish, use `exit` to return to MT's original terminal before accessing MT-private storage.

## 2. Copy the ELF files into private storage

```sh
cd /data/user/0/bin.mt.plus/files/term
mkdir -p pdn
cd pdn
cp /sdcard/Download/pdn .
cp /sdcard/Download/proot-loader .
chmod 700 pdn proot-loader
./pdn version
```

Alternatively, copy both files into that directory using MT's file manager, then set permissions and check the version. Keep their filenames as `pdn` and `proot-loader`; these are standalone programs and do not need `.so` names or an APK installation.

Change the source paths if your downloads are elsewhere. If private storage is inaccessible, check that you are in MT's ordinary terminal and that the package and directory are correct. Deploying to `/data/local/tmp` through Shizuku/ADB shell is a separate entry path.

## 3. Create an entry with fixed storage settings

Still in the `pdn` directory, enter these lines:

```sh
cat > pdn-run <<'EOF'
#!/system/bin/sh
PDN_DIR=$(dirname "$0")
PDN_DIR=$(cd "$PDN_DIR" && pwd) || exit 1
export PDN_ROOTFS_DIR="$PDN_DIR/linux"
export PROOT_TMP_DIR="$PDN_DIR/tmp"
export PROOT_LOADER="$PDN_DIR/proot-loader"
mkdir -p "$PROOT_TMP_DIR" || exit 1
exec "$PDN_DIR/pdn" "$@"
EOF
chmod 700 pdn-run
```

The entry derives its directory from its own location and sets rootfs, temporary storage and loader paths on each invocation, independently of the outer shell's `HOME` or working directory. Installation creates `linux`; the entry creates `tmp`:

```text
files/term/pdn/
├── pdn
├── proot-loader
├── pdn-run
├── pdn-command.sh
├── linux/
└── tmp/
```

For existing installations, keep their original management directory in `PDN_ROOTFS_DIR` so the entry can find them. Do not put the rootfs on `/sdcard`.

`EOF` must be on its own line; the `>` prompt before it means the shell is waiting for file content. Keep assignments on separate lines. Use the filename `pdn-run` directly instead of joining a long directory and filename.

## 4. Register the pdn short command

Create and source the definition file in the same directory:

```sh
cat > pdn-command.sh <<'EOF'
pdn() {
    /data/user/0/bin.mt.plus/files/term/pdn/pdn-run "$@"
}
EOF
chmod 600 pdn-command.sh
. ./pdn-command.sh
pdn version
```

The function forwards arguments to the entry, allowing commands such as `pdn login alpine`. Update its entry path if you change the deployment directory. `./pdn` runs the original ELF directly; use `pdn` or `./pdn-run` for the configured entry.

The definition file persists, but each new terminal session must source it:

```sh
. /data/user/0/bin.mt.plus/files/term/pdn/pdn-command.sh
```

If the terminal supports startup commands, configure that line there. Do not assume Android shell automatically reads `~/.bashrc` or `~/.profile`. The definition file only needs read permission and must be sourced; executing it does not register the function in the current shell.

## 5. Install and enter Linux

```sh
pdn list --available
pdn install alpine
pdn login alpine
```

Run Linux commands after login and use `exit` to return to MT's terminal. Ubuntu is another option:

```sh
pdn install ubuntu
pdn login ubuntu
```

Linux root is a PRoot-emulated identity and remains subject to MT's Android UID and SELinux restrictions. See [distribution management](pdn-distributions.en.md) for sources, resume, command execution, instances and backups, or the [CLI manual](proot-distro-nolib.md) for all options.

## 6. Update and troubleshoot

Exit Linux sessions before replacing `pdn` and `proot-loader` with files from the same new version. Run `chmod 700 pdn proot-loader` and check `pdn version`. Preserve `linux` and both entry files; installed systems do not need reinstalling.

| Symptom | Check |
| --- | --- |
| `pdn: not found` | Source `pdn-command.sh`; the function belongs to the current shell |
| `pdn-command.sh` is missing | Finish creating it, then run `. ./pdn-command.sh` in the program directory |
| `can't create …: Is a directory` | Redirect to a file; use `cat > pdn-command.sh` in the program directory |
| `mkdir: '': No such file or directory` | Read `cat pdn-run` and check that assignments were pasted completely |
| Rootfs path is read-only or inaccessible | Check that you used the entry and that `PDN_ROOTFS_DIR` is writable by the current host |
| ELF cannot execute | Check the ARM64 Android build, execute permissions and the host's permitted private execution directory |

If the system prohibits ELF execution from the current App's private storage, use a permitted host entry. Copying files or emulating root with PRoot does not grant access to another App's private directories.
