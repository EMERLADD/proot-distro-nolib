package id.or.oo.pr.engine

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PdnRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun host(base: File = temporary.newFolder("host with spaces")): ProotHost = object : ProotHost {
        override val nativeLibDir = File(base, "native")
        override val prefixDir = File(base, "prefix")
        override val homeDir = File(base, "home")
        override val cacheDir = File(base, "cache")
        override val packageName = "test.pdn.host"
    }

    private fun binaries(host: ProotHost) {
        host.nativeLibDir.mkdirs()
        for (name in listOf("libpdn.so", "libproot-loader.so")) {
            File(host.nativeLibDir, name).apply {
                writeText("fixture")
                setExecutable(true)
            }
        }
    }

    @Test fun usesHostPathsWithoutPackageInference() {
        val host = host()
        val runtime = PdnRuntime(host)
        val env = runtime.environment()
        assertEquals(File(host.prefixDir, "var/lib/pdn/rootfs"), runtime.rootfsDir)
        assertEquals(File(host.nativeLibDir, "libpdn.so"), runtime.executable)
        assertEquals(File(host.nativeLibDir, "libproot-loader.so"), runtime.loader)
        assertEquals(host.homeDir.absolutePath, env["HOME"])
        assertEquals(runtime.rootfsDir.absolutePath, env["PDN_ROOTFS_DIR"])
        assertEquals(runtime.loader.absolutePath, env["PROOT_LOADER"])
        assertEquals(host.cacheDir.absolutePath, env["PROOT_TMP_DIR"])
        assertEquals("1", env["PROOT_NO_SECCOMP"])
        assertEquals("test.pdn.host", env["APP_PACKAGE"])
    }

    @Test fun acceptsCustomRootfsAndProjectDirectories() {
        val host = host()
        val roots = temporary.newFolder("selected roots")
        val project = temporary.newFolder("selected project")
        val runtime = PdnRuntime(host, roots, project)
        assertEquals(roots.absolutePath, runtime.environment()["PDN_ROOTFS_DIR"])
        assertEquals(listOf(
            "login", "--rootfs", File(roots, "Ubuntu").absolutePath,
            "--user", "1000:1000", "--bind", "${project.absolutePath}:/workspace",
            "--work-dir", "/workspace",
        ), runtime.loginArguments(File(roots, "Ubuntu"), "1000:1000"))
    }

    @Test fun preservesArgumentsAndEmptyValues() {
        val runtime = PdnRuntime(host())
        val arguments = listOf("exec", "ubuntu", "--", "/bin/printf", "%s", "two words", "", "a'\"b", "\$HOME")
        assertEquals(listOf(runtime.executable.absolutePath) + arguments, runtime.command(arguments))
    }

    @Test fun rejectsMissingCommand() {
        assertThrows(IllegalArgumentException::class.java) { PdnRuntime(host()).command(emptyList()) }
    }

    @Test fun rejectsNulArguments() {
        assertThrows(IllegalArgumentException::class.java) { PdnRuntime(host()).command(listOf("exec", "a\u0000b")) }
    }

    @Test fun preparesOnlyHostSelectedDirectories() {
        val host = host()
        binaries(host)
        val runtime = PdnRuntime(host)
        runtime.prepare()
        runtime.prepare()
        for (directory in listOf(host.prefixDir, host.homeDir, host.cacheDir, runtime.rootfsDir, runtime.projectDir)) {
            assertTrue(directory.isDirectory)
        }
        assertFalse(File(temporary.root, "test.pdn.host").exists())
    }

    @Test fun rejectsMissingPdnBeforeCreatingData() {
        val host = host()
        assertThrows(IOException::class.java) { PdnRuntime(host).prepare() }
        assertFalse(host.prefixDir.exists())
    }

    @Test fun rejectsMissingLoader() {
        val host = host()
        binaries(host)
        File(host.nativeLibDir, "libproot-loader.so").delete()
        assertThrows(IOException::class.java) { PdnRuntime(host).prepare() }
    }

    @Test fun rejectsNonExecutableBinary() {
        val host = host()
        binaries(host)
        File(host.nativeLibDir, "libpdn.so").setExecutable(false, false)
        assertThrows(IOException::class.java) { PdnRuntime(host).prepare() }
    }

    @Test fun rejectsFileInsteadOfDataDirectory() {
        val host = host()
        binaries(host)
        host.prefixDir.writeText("keep")
        assertThrows(IOException::class.java) { PdnRuntime(host).prepare() }
        assertEquals("keep", host.prefixDir.readText())
    }

    @Test fun processBuilderKeepsEnvironmentAndArgv() {
        val host = host()
        binaries(host)
        val runtime = PdnRuntime(host)
        val arguments = listOf("exec", "ubuntu", "--", "/bin/printf", "two words")
        val builder = runtime.processBuilder(arguments)
        assertEquals(runtime.command(arguments), builder.command())
        assertEquals(host.homeDir, builder.directory())
        assertEquals(runtime.loader.absolutePath, builder.environment()["PROOT_LOADER"])
        assertFalse(builder.redirectErrorStream())
    }

    @Test fun convenienceApiKeepsArgvAndWorkspace() {
        val host = host()
        binaries(host)
        val pdn = PdnRuntime(host)
        assertEquals(pdn.command(listOf("install", "alpine")), pdn.install("alpine").command())
        assertEquals(pdn.command(listOf("remove", "alpine", "--yes")), pdn.remove("alpine").command())
        val root = File(pdn.rootfsDir, "alpine")
        val login = listOf("login", "alpine") + pdn.loginArguments(root).drop(3)
        assertEquals(pdn.command(login), pdn.login("alpine").command())
        assertEquals(pdn.command(pdn.loginArguments(root, "1000:1000")), pdn.login(root, "1000:1000").command())
        val command = listOf("/bin/printf", "%s", "two words", "", "\$HOME", "a'\"b")
        val expected = listOf("exec") + login.drop(1) + listOf("--") + command
        assertEquals(pdn.command(expected), pdn.exec("alpine", command).command())
        assertEquals(pdn.command(listOf("exec") + pdn.loginArguments(root, "1000:1000").drop(1)
            + listOf("--") + command), pdn.exec(root, command, "1000:1000").command())
        assertEquals(host.cacheDir.absolutePath, pdn.install("alpine").environment()["PROOT_TMP_DIR"])
    }

    @Test fun convenienceApiRejectsOptionsAndEmptyCommands() {
        val pdn = PdnRuntime(host())
        for (name in listOf("", ".hidden", "../alpine", "--rootfs", "alpine ubuntu", "a\u0000b")) {
            assertThrows(IllegalArgumentException::class.java) { pdn.install(name) }
            assertThrows(IllegalArgumentException::class.java) { pdn.remove(name) }
            assertThrows(IllegalArgumentException::class.java) { pdn.login(name) }
            assertThrows(IllegalArgumentException::class.java) { pdn.exec(name, listOf("/bin/true")) }
        }
        assertThrows(IllegalArgumentException::class.java) { pdn.exec("alpine", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { pdn.exec(File("/rootfs"), emptyList()) }
    }

    @Test fun execStartsProcessPreservingArgumentsAndExitCode() {
        val host = host()
        binaries(host)
        val shell = if (File("/system/bin/sh").isFile) "/system/bin/sh" else "/bin/sh"
        File(host.nativeLibDir, "libpdn.so").writeText(
            "#!$shell\nprintf '%s\\n' \"\$@\"\nexit 7\n"
        )
        val pdn = PdnRuntime(host)
        val args = listOf("/bin/printf", "%s", "two words", "", "\$HOME")
        val builder = pdn.exec("alpine", args).redirectErrorStream(true)
        val process = builder.start()
        process.outputStream.close()
        val lines = process.inputStream.bufferedReader().use { it.readLines() }
        assertEquals(builder.command().drop(1), lines)
        assertEquals(7, process.waitFor())
    }

    @Test fun managementApiBuildsCommandsAndRejectsConflictingSources() {
        val host = host()
        binaries(host)
        val pdn = PdnRuntime(host)
        val archive = File(temporary.root, "backup with spaces.tar.gz")
        val cases = listOf(
            pdn.version() to listOf("version"),
            pdn.list() to listOf("list"),
            pdn.list(available = true) to listOf("list", "--available"),
            pdn.mirrors() to listOf("mirrors"),
            pdn.mirrors("ubuntu") to listOf("mirrors", "ubuntu"),
            pdn.install("alpine", mirror = "official") to listOf("install", "alpine", "--mirror", "official"),
            pdn.install("alpine", archive = archive) to listOf("install", "alpine", "--archive", archive.absolutePath),
            pdn.backup("alpine", archive) to listOf("backup", "alpine", archive.absolutePath),
            pdn.restore("restored", archive) to listOf("restore", "restored", archive.absolutePath),
            pdn.config("alpine") to listOf("config", "alpine", "--show"),
            pdn.clearConfig("alpine") to listOf("config", "alpine", "--clear"),
            pdn.saveConfig("alpine", listOf("--user", "1000:1000")) to listOf("config", "alpine", "--user", "1000:1000"),
        )
        for ((builder, args) in cases) assertEquals(pdn.command(args), builder.command())
        assertThrows(IllegalArgumentException::class.java) { pdn.install("alpine", "official", archive) }
        assertThrows(IllegalArgumentException::class.java) { pdn.install("alpine", "") }
        assertThrows(IllegalArgumentException::class.java) { pdn.saveConfig("alpine", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { pdn.mirrors("--help") }
        assertThrows(IllegalArgumentException::class.java) { pdn.backup("../x", archive) }
        assertThrows(IllegalArgumentException::class.java) { pdn.restore("../x", archive) }
        assertThrows(IllegalArgumentException::class.java) { pdn.config("../x") }
        assertThrows(IllegalArgumentException::class.java) { pdn.clearConfig("../x") }
        assertThrows(IllegalArgumentException::class.java) { pdn.saveConfig("../x", listOf("--user", "root")) }
    }
}
