# Frequently asked questions

[简体中文](pdn-faq.md) | English · [Back to README](../README.en.md)

## Contents

- [Downloaded file will not execute](#downloaded-file-will-not-execute)
- [Version still shows an old release](#version-still-shows-an-old-release)
- [Rootfs or temporary directory unavailable](#rootfs-or-temporary-directory-unavailable)
- [Packaging in an App](#packaging-in-an-app)
- [Rish will not execute inside Ubuntu](#rish-will-not-execute-inside-ubuntu)
- [Shizuku disconnects in the background](#shizuku-disconnects-in-the-background)
- [Root and Android permissions](#root-and-android-permissions)
- [X11 points to itself](#x11-points-to-itself)
- [Reporting errors](#reporting-errors)

## Downloaded file will not execute

Check that the file is the ARM64 Android PDN executable, not source or a rootfs archive. Shared storage may prohibit execution; `chmod 755` does not override filesystem or SELinux restrictions. Copy it to an executable directory allowed by the host, or package it in an App's `nativeLibraryDir`.

```sh
chmod 755 /allowed/executable/directory/pdn
/allowed/executable/directory/pdn version
```

Use a full path when the program is not in `PATH`. MT's example path is `/data/user/0/bin.mt.plus/files/term/bin/pdn`, accessible from MT's own terminal; Android shell does not automatically have access to that private directory. With shell privileges, `/data/local/tmp` is an option. See the [tutorial](pdn-shizuku-android-shell.en.md).

## Version still shows an old release

Downloading a new file does not replace earlier copies in other directories. An AAR or APK may also contain the old program. Check what is being invoked:

```sh
command -v pdn
pdn version
pdn --version
```

Exit old sessions, replace matching PDN, loader, `.so` or AAR files, and check again. App integration also requires rebuilding and reinstalling. `Based on PRoot 5.4.0-pr` is the underlying engine version, separate from PDN.

## Rootfs or temporary directory unavailable

Android shell may set `HOME=/`, which is not writable. Choose explicit accessible directories; do not store rootfs on `/sdcard`. For example, in UID 2000 Android shell:

```sh
export PDN_ROOTFS_DIR=/data/local/tmp/pdn/linux
export PROOT_TMP_DIR=/data/local/tmp/pdn/tmp
mkdir -p "$PROOT_TMP_DIR"
```

Use the reported path, reason and suggestion rather than blindly applying `chmod 777`. Other causes include insufficient space, a file occupying a directory name, or unsupported filesystem semantics. PDN does not silently switch storage locations after failure. See [error classification and tests (Chinese)](pdn-error-testing.md).

## Packaging in an App

The AAR provides Java/Kotlin wrappers, PDN, loader and PTY JNI. The host needs the Kotlin standard library, appropriate permissions and its own UI. The AAR does not include a full distribution or ready-made terminal GUI.

For direct `.so` integration, put `libpdn.so` and `libproot-loader.so` under `jniLibs/arm64-v8a/`, ensure extraction to `nativeLibraryDir`, and launch them as processes. They are ELF executables, not a PDN JNI API for `System.loadLibrary`. The actual PTY JNI library handles terminal input, output and resizing.

See [Android embedding (Chinese)](android-embedding.md), [AAR API (Chinese)](pdn-aar-api.md), the [AAR example](../examples/aar-probe/README.md) and the [.so example](../examples/so-probe/README.md).

## Rish will not execute inside Ubuntu

The original Shizuku rish script specifies `/system/bin/sh` and also needs `app_process` and its dex file. If these Android paths are absent, an existing executable script can still fail because its interpreter cannot be found.

The verified flow connects the original rish from the host first, then starts PDN from Android shell so Linux inherits its privileges, with the required Android paths bound:

```sh
./pdn login ubuntu --bind /system --bind /apex --bind /linkerconfig/ld.config.txt
```

Those paths exist in the tested environment; check the layout on other systems. In Ubuntu, wrap the Android shell invocation in a function:

```sh
rish() {
    /system/bin/sh -c 'export PATH=/system/bin:/system/xbin; exec /system/bin/sh "$@"' -- "$@"
}
rish -c 'getprop ro.build.version.sdk'
```

This is a custom function, not the original client reconnecting to Shizuku. Android queries and a temporary `settings` write were verified; this does not establish that Ubuntu's own `adb` client was tested. Initial rish setup, persistence, write verification and cleanup are in the [complete tutorial](pdn-shizuku-android-shell.en.md).

## Shizuku disconnects in the background

During the MT reproduction, the authorized host disconnected when moved to the background, even with battery usage set to unrestricted. Keep that host in the foreground throughout this tutorial. After a disconnect, check Shizuku and reconnect.

This is observed behavior in the tested environment, not a universal rule for every Android ROM. Authorization or relaxed battery settings do not guarantee background connectivity for this flow.

## Root and Android permissions

Ubuntu's root prompt reflects a PRoot-simulated identity. It does not grant an ordinary App actual Android root or bypass SELinux. In this flow Shizuku/rish provided UID 2000 Android shell privileges, inherited by Linux at startup. Available operations remain subject to system restrictions.

PRoot is not a container security boundary. Changes to bound directories can affect host files directly. Consider the accessible host paths before running untrusted software.

## X11 points to itself

Some distributions retain the historical `/usr/bin/X11 -> .` compatibility symlink. It points to the current directory, is not a PDN self-bind, and does not imply a graphical desktop is installed. Check with:

```sh
ls -ld /bin/X11 /usr/bin/X11
readlink /usr/bin/X11
```

## Reporting errors

Provide the host App, Android version, PDN version, reproduction commands and full errors in [Issues](https://github.com/EMERLADD/proot-distro-nolib/issues). Do not include passwords, tokens, device serials or other private information. A missing-interpreter error alone cannot identify which process caused a native crash; preserve the complete output for separate investigation.
