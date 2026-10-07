package id.or.oo.pr.engine

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AlpinePackagesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun runtime(): PdnRuntime {
        val base = temporary.newFolder("host with spaces")
        val host = object : ProotHost {
            override val nativeLibDir = File(base, "native")
            override val prefixDir = File(base, "prefix")
            override val homeDir = File(base, "home")
            override val cacheDir = File(base, "cache")
            override val packageName = "test.alpine.gui"
        }
        host.nativeLibDir.mkdirs()
        for (name in listOf("libpdn.so", "libproot-loader.so")) {
            File(host.nativeLibDir, name).apply { writeText("fixture"); setExecutable(true) }
        }
        return PdnRuntime(host)
    }

    @Test fun acceptsPackageNamesAndRemovesDuplicates() {
        assertEquals(listOf("git", "curl", "libstdc++", "python3-dev"),
            AlpinePackages.packageNames(" git\n curl\tlibstdc++ python3-dev git "))
    }

    @Test fun rejectsCommandsOptionsAndPaths() {
        for (input in listOf("", "  ", "--help", "-x", "git;id", "\$(id)", "a/b", "'curl'", "git\u0000")) {
            assertThrows(IllegalArgumentException::class.java) { AlpinePackages.packageNames(input) }
        }
    }

    @Test fun buildsShellCommandWithPackagesAsSeparateArguments() {
        val runtime = runtime()
        val root = File(runtime.rootfsDir, "Alpine")
        val packages = AlpinePackages(runtime, root)
        val builder = packages.install("git curl")
        val args = builder.command()
        assertTrue(args.contains(root.absolutePath))
        assertTrue(args.contains("${runtime.projectDir.absolutePath}:/workspace"))
        val command = args.drop(args.indexOf("--") + 1)
        assertEquals(listOf("/bin/sh", "-c", "apk update && exec apk add -- \"\$@\"", "pdn-apk", "git", "curl"), command)
        assertEquals(runtime.loader.absolutePath, builder.environment()["PROOT_LOADER"])
        assertEquals(listOf("/bin/sh", "-c", "exec apk update"), packages.update().command().takeLast(3))
        assertEquals(listOf("/bin/sh", "-c", "exec apk info"), packages.installed().command().takeLast(3))
        assertThrows(IllegalArgumentException::class.java) { packages.install("git;id") }
    }

    @Test fun shellStopsOnIndexFailureAndPreservesExitStatus() {
        val runtime = runtime()
        val root = File(runtime.rootfsDir, "alpine")
        val packages = AlpinePackages(runtime, root)
        val script = packages.install("git curl").command().takeLast(4).first()
        val shell = if (File("/system/bin/sh").isFile) "/system/bin/sh" else "/bin/sh"
        val bin = temporary.newFolder("fake-apk")
        File(bin, "apk").apply {
            writeText("#!$shell\nprintf '%s\\n' \"\$*\"\nif [ \"\$1\" = update ]; then exit \"\$INDEX_STATUS\"; fi\nexit \"\$ADD_STATUS\"\n")
            setExecutable(true)
        }
        for ((indexStatus, addStatus) in listOf(0 to 0, 8 to 0, 0 to 9)) {
            val builder = ProcessBuilder(shell, "-c", script, "pdn-apk", "git", "curl").redirectErrorStream(true)
            builder.environment()["PATH"] = "${bin.absolutePath}:/system/bin:/bin"
            builder.environment()["INDEX_STATUS"] = indexStatus.toString()
            builder.environment()["ADD_STATUS"] = addStatus.toString()
            val process = builder.start()
            process.outputStream.close()
            val lines = process.inputStream.bufferedReader().use { it.readLines() }
            assertEquals(if (indexStatus == 0) listOf("update", "add -- git curl") else listOf("update"), lines)
            assertEquals(if (indexStatus != 0) indexStatus else addStatus, process.waitFor())
        }
    }
}
