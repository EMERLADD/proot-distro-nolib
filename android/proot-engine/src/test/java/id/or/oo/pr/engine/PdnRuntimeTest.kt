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
}
