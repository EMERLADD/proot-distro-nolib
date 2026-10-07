package id.or.oo.pr.engine

import java.io.File
import java.io.IOException

class PdnRuntime(
    private val host: ProotHost,
    val rootfsDir: File = File(host.prefixDir, "var/lib/pdn/rootfs"),
    val projectDir: File = File(host.homeDir, "workspace"),
) {
    val executable: File get() = File(host.nativeLibDir, "libpdn.so")
    val loader: File get() = File(host.nativeLibDir, "libproot-loader.so")

    fun environment(): Map<String, String> = linkedMapOf(
        "APP_PREFIX" to host.prefixDir.absolutePath,
        "APP_HOME" to host.homeDir.absolutePath,
        "APP_PACKAGE" to host.packageName,
        "HOME" to host.homeDir.absolutePath,
        "PATH" to "${File(host.prefixDir, "bin").absolutePath}:/system/bin:/system/xbin",
        "PDN_ROOTFS_DIR" to rootfsDir.absolutePath,
        "PROOT_LOADER" to loader.absolutePath,
        "PROOT_NO_SECCOMP" to "1",
        "PROOT_TMP_DIR" to host.cacheDir.absolutePath,
        "TMPDIR" to host.cacheDir.absolutePath,
        "TERM" to "xterm-256color",
        "LANG" to "en_US.UTF-8",
    )

    fun command(arguments: List<String>): List<String> {
        require(arguments.isNotEmpty()) { "A pdn command is required" }
        require(arguments.all { '\u0000' !in it }) { "Arguments cannot contain NUL" }
        return listOf(executable.absolutePath) + arguments
    }

    fun loginArguments(rootfs: File, user: String = "root"): List<String> = listOf(
        "login", "--rootfs", rootfs.absolutePath,
        "--user", user, "--bind", "${projectDir.absolutePath}:/workspace",
        "--work-dir", "/workspace",
    )

    fun prepare() {
        for (binary in listOf(executable, loader)) {
            if (!binary.isFile || !binary.canExecute()) {
                throw IOException("Native executable is missing or cannot run: ${binary.name}")
            }
        }
        for (directory in listOf(host.prefixDir, host.homeDir, host.cacheDir, rootfsDir, projectDir)) {
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("Cannot prepare directory: ${directory.name}")
            }
            if (!directory.canWrite() || !directory.canExecute()) {
                throw IOException("Directory is not writable: ${directory.name}")
            }
        }
    }

    fun processBuilder(arguments: List<String>): ProcessBuilder {
        val command = command(arguments)
        prepare()
        return ProcessBuilder(command).apply {
            directory(host.homeDir)
            environment().putAll(this@PdnRuntime.environment())
        }
    }
}
