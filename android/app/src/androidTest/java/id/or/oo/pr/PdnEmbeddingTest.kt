package id.or.oo.pr

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import id.or.oo.pr.engine.PdnRuntime
import id.or.oo.pr.engine.ProotHost
import id.or.oo.pr.engine.PtyNative
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdnEmbeddingTest {
    private lateinit var base: File
    private lateinit var runtime: PdnRuntime
    private lateinit var rootfs: File

    @Before fun prepare() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        base = File(context.filesDir, "pdn-w1-test-${System.nanoTime()}")
        val host = object : ProotHost {
            override val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
            override val prefixDir = File(base, "prefix")
            override val homeDir = File(base, "home")
            override val cacheDir = File(base, "cache")
            override val packageName = context.packageName
        }
        runtime = PdnRuntime(host)
        runtime.prepare()
        rootfs = File(runtime.rootfsDir, "fixture")
        for (name in listOf("bin", "root", "tmp", "etc")) File(rootfs, name).mkdirs()
        File(host.nativeLibDir, "libbusybox.so").copyTo(File(rootfs, "bin/busybox"))
        File(rootfs, "bin/busybox").setExecutable(true)
        File(rootfs, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/sh\n")
        Os.symlink("busybox", File(rootfs, "bin/sh").absolutePath)
    }

    @After fun cleanup() { if (::base.isInitialized) base.deleteRecursively() }

    private fun execute(arguments: List<String>): Pair<Int, String> {
        val process = runtime.processBuilder(arguments).redirectErrorStream(true).start()
        process.outputStream.close()
        val executor = Executors.newSingleThreadExecutor()
        val output = executor.submit<String> { process.inputStream.bufferedReader().use { it.readText() } }
        try {
            assertTrue("pdn did not exit", process.waitFor(30, TimeUnit.SECONDS))
            return process.exitValue() to output.get(5, TimeUnit.SECONDS)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            executor.shutdownNow()
        }
    }

    @Test fun packagedCommandsUsePdn() {
        val help = execute(listOf("help"))
        assertEquals(help.second, 0, help.first)
        assertTrue(help.second.contains("pdn exec"))
        val version = execute(listOf("version"))
        assertEquals(version.second, 0, version.first)
        assertTrue(version.second.contains("proot-distro-nolib"))
    }

    @Test fun guestExecPreservesArgumentsProjectAndStatus() {
        val args = listOf(
            "exec", "--rootfs", rootfs.absolutePath,
            "--bind", "${runtime.projectDir.absolutePath}:/workspace", "--work-dir", "/workspace",
            "--env", "W1_VALUE=two words", "--", "/bin/sh", "-c",
            "printf '%s\\n' \"\$1\" \"\$W1_VALUE\"; echo saved > /workspace/result; /bin/busybox id -u; exit 7",
            "fixture", "a 'quoted' value",
        )
        val result = execute(args)
        assertEquals(result.second, 7, result.first)
        assertTrue(result.second.contains("a 'quoted' value\ntwo words\n"))
        assertTrue(result.second.trim().endsWith("0"))
        assertEquals("saved\n", File(runtime.projectDir, "result").readText())
    }

    @Test fun ptyStartsPackagedPdnWithExternalLoader() {
        val args = runtime.command(runtime.loginArguments(rootfs) + listOf(
            "--", "/bin/sh", "-c", "printf 'PTY-W1:%s\\n' \"\$1\"; /bin/busybox stty size", "fixture", "two words",
        ))
        val environment = runtime.environment().flatMap { listOf(it.key, it.value) }.toTypedArray()
        val fd = PtyNative.forkPty(args[0], args.toTypedArray(), environment, 24, 42)
        assertTrue("PTY startup failed", fd >= 0)
        val pid = PtyNative.getPid()
        val executor = Executors.newSingleThreadExecutor()
        val output = executor.submit<String> {
            val text = StringBuilder()
            val buffer = ByteArray(4096)
            while (true) {
                val length = PtyNative.read(fd, buffer, 0, buffer.size)
                if (length < 0) break
                if (length > 0) text.append(String(buffer, 0, length))
            }
            text.toString()
        }
        try {
            val text = output.get(30, TimeUnit.SECONDS)
            assertTrue(text, text.contains("PTY-W1:two words"))
            assertTrue(text, text.contains("24 42"))
            assertFalse(text, text.contains("option -i/-0/-S was already specified"))
        } finally {
            PtyNative.close(fd)
            executor.shutdownNow()
            PtyNative.waitPid(pid)
        }
    }
}
