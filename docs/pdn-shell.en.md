# Use PDN in an Android shell

[简体中文](pdn-shell.md) | English · [Back to README](../README.en.md)

Download matching raw `pdn` and `proot-loader` files from [Releases](https://github.com/EMERLADD/proot-distro-nolib/releases). This page covers deployment, installation and Linux management in a shell with execution and storage access. To call Android debugging commands from Linux, see the [Android debugging guide](pdn-shizuku-android-shell.en.md).

## Deploy to Android shell

This example applies to Android shell environments permitted to execute from `/data/local/tmp`, such as ADB or an already connected Shizuku/rish shell. Ordinary terminal Apps should use their own accessible executable directories. Do not try to initialize the original rish in App-private storage after entering Android shell.

Download `pdn` and `proot-loader`, then adjust the two source paths to their actual locations:

```sh
mkdir -p /data/local/tmp/pdn
cp /sdcard/Download/pdn /data/local/tmp/pdn/pdn
cp /sdcard/Download/proot-loader /data/local/tmp/pdn/proot-loader
chmod 755 /data/local/tmp/pdn/pdn /data/local/tmp/pdn/proot-loader
cd /data/local/tmp/pdn
./pdn version
```

Select Linux and temporary directories:

```sh
export PDN_ROOTFS_DIR=/data/local/tmp/pdn/linux
export PROOT_TMP_DIR=/data/local/tmp/pdn/tmp
export PROOT_LOADER=/data/local/tmp/pdn/proot-loader
mkdir -p "$PROOT_TMP_DIR"
```

The current working directory does not automatically determine rootfs storage. Android shell may set `HOME=/`, so configure paths explicitly. See the [Android shell tutorial](pdn-shizuku-android-shell.en.md) for complete steps and the MT example.

The AAR accepts document URIs, descriptors and streams for archive import and backup export. The example App includes document-picker buttons; see the [AAR API](pdn-aar-api.en.md).

Use `pdn recovery list` to inspect interrupted installation staging and `pdn recovery clear --yes` for explicit cleanup. Installed systems, unknown entries and resume cache are preserved; see [distribution management](pdn-distributions.en.md#interrupted-installation-checks-and-cleanup).

## Keep directory settings for later use

`export` affects only the current shell and its children. Create a dedicated entry in the outer Android shell to reuse the same paths. Change into the deployment directory first, then enter the short lines:

```sh
cd /data/local/tmp/pdn
cat > pdn-run <<'EOF'
#!/system/bin/sh
PDN_DIR=/data/local/tmp/pdn
export PDN_ROOTFS_DIR="$PDN_DIR/linux"
export PROOT_TMP_DIR="$PDN_DIR/tmp"
mkdir -p "$PROOT_TMP_DIR" || exit 1
exec "$PDN_DIR/pdn" "$@"
EOF
chmod 700 pdn-run

./pdn-run install alpine
./pdn-run login alpine
```

`EOF` must be on its own line. A `>` prompt before the closing `EOF` means the shell is waiting for file content. Keep variable assignments on separate lines to avoid losing parts of long pasted commands. Use the filename `pdn-run`; do not join the directory and filename with a space.

The entry sets directories on every invocation. For an MT deployment at `/data/local/tmp/pdn-mt`, change the `cd` directory, `PDN_DIR` and the entry path in the function below. Keep the rootfs location used for existing installations. Native Android shell does not guarantee loading `~/.bashrc` or `~/.profile`.

### Use pdn as the short command

After creating `pdn-run`, define a function in the outer Android shell:

```sh
pdn() { /data/local/tmp/pdn/pdn-run "$@"; }
pdn install alpine
pdn login alpine
```

Save the function definition and load it into the current session:

```sh
cd /data/local/tmp/pdn
cat > pdn-command.sh <<'EOF'
pdn() { /data/local/tmp/pdn/pdn-run "$@"; }
EOF
chmod 600 pdn-command.sh
. ./pdn-command.sh
pdn login alpine
```

After reconnecting through rish, load the definition once:

```sh
. /data/local/tmp/pdn/pdn-command.sh
pdn login alpine
```

If your terminal or connection tool supports initialization commands, configure the source command to run **after entering rish**. The definition file persists but is not loaded automatically. The function calls `pdn-run`, which sets rootfs and temporary directories on each launch. Use `pdn` for the short command; `./pdn` runs the original executable directly and bypasses this entry.

If startup reports `mkdir: '': No such file or directory`, the temporary directory variable is empty. Check that the files were pasted completely:

```sh
cd /data/local/tmp/pdn
cat pdn-run
cat pdn-command.sh
```

Rewrite `pdn-run` with the complete content above and retry `pdn login alpine`. Sourcing a definition file before it exists reports a missing file; finish `cat … EOF` before running `. ./pdn-command.sh`. For a dedicated Ubuntu entry with Android debugging mounts, see the [Android debugging tutorial](pdn-shizuku-android-shell.en.md#save-directory-variables-and-a-dedicated-login-entry).

## Install, log in and manage distributions

```sh
pdn list --available
pdn install ubuntu
pdn login ubuntu
```

After exiting Linux, execute a command or manage the system from the host shell:

```sh
pdn exec ubuntu -- /bin/sh -c 'echo hello; uname -r'
pdn mirrors ubuntu
pdn list
```

After an online download is interrupted or cancelled, repeat the same installation with the same rootfs management directory to automatically attempt resuming from the same mirror URL. There is no automatic background retry. Extraction requires the full pinned size and SHA256 to match; see [distribution management](pdn-distributions.en.md).

Alpine, Ubuntu Base, Debian slim and Arch Linux ARM are supported. See [distribution management](pdn-distributions.en.md) for official sources, offline archives, binds, accounts, default configuration, backups and the full command index.

Install the same distribution under separate names, each with its own rootfs:

```sh
pdn install alpine --name ai-python
pdn install alpine --name ai-node
pdn login ai-python
pdn list --json
```

Names contain up to 128 ASCII letters, digits, underscores, dots or hyphens, starting with a letter, digit or underscore. Collisions are case insensitive. New instances record a stable ID, provenance, version, digest and creation time. Legacy rootfs queries return `instance: null` without modifying them; restoring a backup creates a fresh ID.

Cloning creates a new ID. Renaming keeps the original ID and migrates rootfs-owned links and saved bind paths. Exit active instance sessions first:

```sh
pdn clone ai-python ai-python-test
pdn rename ai-python-test workspace-python
pdn login workspace-python
```

