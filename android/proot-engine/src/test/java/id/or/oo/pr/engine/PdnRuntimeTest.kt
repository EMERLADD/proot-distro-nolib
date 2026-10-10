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
    @Test fun namedInstallsPreserveSourceOptionsAndValidateAsciiBoundaries() {
        val host = host()
        binaries(host)
        val runtime = PdnRuntime(host)
        val archive = File(temporary.root, "local archive.tar.gz")
        val name = "A" + "a".repeat(127)
        assertEquals(runtime.command(listOf("install", "alpine", "--name", name)), runtime.installAs("alpine", name).command())
        assertEquals(runtime.command(listOf("install", "alpine", "--name", "ai-python", "--mirror", "official")),
            runtime.installAs("alpine", "ai-python", "official").command())
        assertEquals(runtime.command(listOf("install", "alpine", "--name", "_instance.1", "--archive", archive.absolutePath)),
            runtime.installAs("alpine", "_instance.1", archive = archive).command())
        for (invalid in listOf("", "a".repeat(129), "中文", "é", ".hidden", "--help", "../a", "a b", "a\u0000b", "a\nb")) {
            assertThrows(IllegalArgumentException::class.java) { runtime.installAs("alpine", invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) { runtime.installAs("--help", "alias") }
        assertThrows(IllegalArgumentException::class.java) { runtime.installAs("alpine", "alias", "official", archive) }
        for (mirror in listOf("", " ", "--help", "a\u0000b")) {
            assertThrows(IllegalArgumentException::class.java) { runtime.installAs("alpine", "alias", mirror) }
        }
    }

    @Test fun cloneAndRenameBuildIndependentLiteralCommands() {
        val host = host()
        binaries(host)
        val runtime = PdnRuntime(host)
        val legacy = "a".repeat(129)
        val boundary = "_" + "a".repeat(127)
        for ((builder, arguments) in listOf(
            runtime.clone("ai-python", "ai-python-test") to listOf("clone", "ai-python", "ai-python-test"),
            runtime.rename("ai-python", "workspace.1") to listOf("rename", "ai-python", "workspace.1"),
            runtime.clone(legacy, boundary) to listOf("clone", legacy, boundary),
            runtime.rename(legacy, boundary) to listOf("rename", legacy, boundary),
        )) {
            assertEquals(runtime.command(arguments), builder.command())
            assertEquals(host.homeDir, builder.directory())
            assertEquals(runtime.rootfsDir.absolutePath, builder.environment()["PDN_ROOTFS_DIR"])
            assertFalse(builder.redirectErrorStream())
        }
    }

    @Test fun cloneAndRenameValidateBothNamesBeforePreparingDirectories() {
        val host = host()
        val runtime = PdnRuntime(host)
        val invalidNames = listOf("", "../root", ".hidden", "--option", "a b", "a\u0000b", "a\nb", "中文", "é")
        for (name in invalidNames) {
            assertThrows(IllegalArgumentException::class.java) { runtime.clone(name, "target") }
            assertThrows(IllegalArgumentException::class.java) { runtime.rename(name, "target") }
        }
        for (name in invalidNames + "a".repeat(129)) {
            assertThrows(IllegalArgumentException::class.java) { runtime.clone("source", name) }
            assertThrows(IllegalArgumentException::class.java) { runtime.rename("source", name) }
        }
        assertFalse(host.prefixDir.exists())
        assertFalse(host.homeDir.exists())
    }

    @Test fun configurationUsesLiteralArgvAndWholeOperationOverrides() {
        val host = host()
        binaries(host)
        val binds = mutableListOf(PdnBind(File("/host with spaces"), "/guest with spaces"))
        val environment = linkedMapOf("VALUE" to "$(echo x) a=b", "EMPTY" to "")
        val configuration = PdnConfiguration("1000:1000", "/guest with spaces", binds, environment)
        val runtime = PdnRuntime(host, File(host.prefixDir, "roots"), File(host.homeDir, "project"), configuration)
        binds.clear()
        environment.clear()
        val root = File(runtime.rootfsDir, "alpine")
        val options = listOf("--user", "1000:1000", "--bind", "${runtime.projectDir.absolutePath}:/workspace",
            "--bind", "/host with spaces:/guest with spaces", "--work-dir", "/guest with spaces",
            "--env", "VALUE=$(echo x) a=b", "--env", "EMPTY=")
        assertEquals(listOf("login", "--rootfs", root.absolutePath) + options, runtime.loginArguments(root))
        assertEquals(runtime.command(listOf("login", "alpine") + options), runtime.login("alpine").command())
        assertEquals(runtime.command(listOf("exec", "alpine") + options + listOf("--", "/bin/printf", "a b")),
            runtime.exec("alpine", listOf("/bin/printf", "a b")).command())
        assertEquals("root", runtime.loginArguments(root, "root")[4])
        assertTrue(runtime.loginArguments(root, "root").contains("VALUE=$(echo x) a=b"))
        val selected = PdnConfiguration("root", "/workspace", listOf(PdnBind(File("/new mount"), "/workspace")), emptyMap())
        val selectedArgs = listOf("login", "--rootfs", root.absolutePath, "--user", "root", "--bind", "/new mount:/workspace", "--work-dir", "/workspace")
        assertEquals(selectedArgs, runtime.loginArguments(root, selected))
        assertEquals(runtime.command(selectedArgs), runtime.login(root, selected).command())
        assertEquals(runtime.command(listOf("login", "alpine") + selectedArgs.drop(3)), runtime.login("alpine", selected).command())
        assertEquals(runtime.command(listOf("exec") + selectedArgs.drop(1) + listOf("--", "/bin/true")), runtime.exec(root, listOf("/bin/true"), selected).command())
        assertEquals(runtime.command(listOf("exec", "alpine") + selectedArgs.drop(3) + listOf("--", "/bin/true")), runtime.exec("alpine", listOf("/bin/true"), selected).command())
        assertFalse(runtime.login(root, selected).environment().containsKey("VALUE"))
        assertThrows(IllegalArgumentException::class.java) { runtime.exec(root, emptyList(), selected) }
        assertThrows(IllegalArgumentException::class.java) { runtime.exec("alpine", emptyList(), selected) }
        assertThrows(IllegalArgumentException::class.java) { runtime.loginArguments(root, "a\u0000b") }
    }

}
