package id.or.oo.pr.engine

import java.io.File
import java.io.IOException

class PdnRuntime @JvmOverloads constructor(
    private val host: ProotHost,
    val rootfsDir: File = File(host.prefixDir, "var/lib/pdn/rootfs"),
    val projectDir: File = File(host.homeDir, "workspace"),
    val configuration: PdnConfiguration,
) {
    @JvmOverloads
    constructor(
        host: ProotHost,
        rootfsDir: File = File(host.prefixDir, "var/lib/pdn/rootfs"),
        projectDir: File = File(host.homeDir, "workspace"),
    ) : this(host, rootfsDir, projectDir, PdnConfiguration())

    fun catalog(): PdnCatalog = PdnCatalog(this)

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

    @JvmOverloads
    fun loginArguments(rootfs: File, user: String = configuration.user): List<String> =
        loginArguments(rootfs, PdnConfiguration(user, configuration.workDir, configuration.binds, configuration.environment))

    fun loginArguments(rootfs: File, configuration: PdnConfiguration): List<String> {
        val args = mutableListOf("login", "--rootfs", rootfs.absolutePath, "--user", configuration.user)
        if (configuration.binds.none { it.guestPath == "/workspace" }) {
            val workspace = PdnBind(projectDir, "/workspace")
            args += listOf("--bind", "${workspace.hostFile.absolutePath}:${workspace.guestPath}")
        }
        for (bind in configuration.binds) args += listOf("--bind", "${bind.hostFile.absolutePath}:${bind.guestPath}")
        args += listOf("--work-dir", configuration.workDir)
        for ((key, value) in configuration.environment) args += listOf("--env", "$key=$value")
        require(args.all { '\u0000' !in it }) { "Arguments cannot contain NUL" }
        return args
    }

    private fun distroName(name: String): String {
        require(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*").matches(name)) { "Invalid distro name: $name" }
        return name
    }

    fun version(): ProcessBuilder = processBuilder(listOf("version"))

    @JvmOverloads
    fun list(available: Boolean = false): ProcessBuilder =
        processBuilder(if (available) listOf("list", "--available") else listOf("list"))

    @JvmOverloads
    fun mirrors(name: String? = null): ProcessBuilder =
        processBuilder(if (name == null) listOf("mirrors") else listOf("mirrors", distroName(name)))

    @JvmOverloads
    fun install(name: String, mirror: String? = null, archive: File? = null): ProcessBuilder {
        val args = mutableListOf("install", distroName(name))
        require(mirror == null || archive == null) { "Choose either a mirror or a local archive" }
        if (mirror != null) {
            require(mirror.isNotBlank() && !mirror.startsWith("-")) { "A mirror name is required" }
            args += listOf("--mirror", mirror)
        }
        if (archive != null) args += listOf("--archive", archive.absolutePath)
        return processBuilder(args)
    }

    @JvmOverloads
    fun installAs(distro: String, instanceName: String, mirror: String? = null, archive: File? = null): ProcessBuilder {
        require(instanceName.length <= 128 && Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*").matches(instanceName)) { "Invalid instance name: $instanceName" }
        val args = mutableListOf("install", distroName(distro), "--name", instanceName)
        require(mirror == null || archive == null) { "Choose either a mirror or a local archive" }
        if (mirror != null) {
            require(mirror.isNotBlank() && !mirror.startsWith("-")) { "A mirror name is required" }
            args += listOf("--mirror", mirror)
        }
        if (archive != null) args += listOf("--archive", archive.absolutePath)
        return processBuilder(args)
    }

    fun remove(name: String): ProcessBuilder = processBuilder(listOf("remove", distroName(name), "--yes"))

    @JvmOverloads
    fun login(name: String, user: String = configuration.user): ProcessBuilder =
        processBuilder(listOf("login", distroName(name)) + loginArguments(File(rootfsDir, name), user).drop(3))

    @JvmOverloads
    fun login(rootfs: File, user: String = configuration.user): ProcessBuilder = processBuilder(loginArguments(rootfs, user))

    @JvmOverloads
    fun exec(name: String, command: List<String>, user: String = configuration.user): ProcessBuilder {
        require(command.isNotEmpty()) { "A guest command is required" }
        return processBuilder(listOf("exec", distroName(name)) + loginArguments(File(rootfsDir, name), user).drop(3)
            + listOf("--") + command)
    }

    @JvmOverloads
    fun exec(rootfs: File, command: List<String>, user: String = configuration.user): ProcessBuilder {
        require(command.isNotEmpty()) { "A guest command is required" }
        return processBuilder(listOf("exec") + loginArguments(rootfs, user).drop(1) + listOf("--") + command)
    }

    fun login(name: String, configuration: PdnConfiguration): ProcessBuilder =
        processBuilder(listOf("login", distroName(name)) + loginArguments(File(rootfsDir, name), configuration).drop(3))

    fun login(rootfs: File, configuration: PdnConfiguration): ProcessBuilder =
        processBuilder(loginArguments(rootfs, configuration))

    fun exec(name: String, command: List<String>, configuration: PdnConfiguration): ProcessBuilder {
        require(command.isNotEmpty()) { "A guest command is required" }
        return processBuilder(listOf("exec", distroName(name)) + loginArguments(File(rootfsDir, name), configuration).drop(3)
            + listOf("--") + command)
    }

    fun exec(rootfs: File, command: List<String>, configuration: PdnConfiguration): ProcessBuilder {
        require(command.isNotEmpty()) { "A guest command is required" }
        return processBuilder(listOf("exec") + loginArguments(rootfs, configuration).drop(1) + listOf("--") + command)
    }

    fun backup(name: String, archive: File): ProcessBuilder =
        processBuilder(listOf("backup", distroName(name), archive.absolutePath))

    fun restore(name: String, archive: File): ProcessBuilder =
        processBuilder(listOf("restore", distroName(name), archive.absolutePath))

    fun config(name: String): ProcessBuilder = processBuilder(listOf("config", distroName(name), "--show"))

    fun clearConfig(name: String): ProcessBuilder = processBuilder(listOf("config", distroName(name), "--clear"))

    fun saveConfig(name: String, options: List<String>): ProcessBuilder {
        require(options.isNotEmpty()) { "Configuration options are required" }
        return processBuilder(listOf("config", distroName(name)) + options)
    }

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
