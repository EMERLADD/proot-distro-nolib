package id.or.oo.pr.engine

import android.util.Log
import java.io.File

class ProotLauncher(private val host: ProotHost) {

    companion object {
        private const val TAG = "PR"
    }

    val prefixDir: File
        get() = host.prefixDir

    fun startPdnSession(
        rootfs: File,
        user: String = "root",
        rows: Int = 24,
        cols: Int = 80,
    ): Session? {
        val runtime = PdnRuntime(host)
        return try {
            runtime.prepare()
            val args = runtime.command(runtime.loginArguments(rootfs, user))
            startCustomSession(args, rows, cols)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start pdn session", e)
            null
        }
    }

    fun startPdnSession(
        rootfs: File,
        configuration: PdnConfiguration,
        rows: Int = 24,
        cols: Int = 80,
    ): PdnTerminalSession {
        val runtime = PdnRuntime(host)
        runtime.prepare()
        return PdnTerminalSession.start(runtime.login(rootfs, configuration), rows, cols)
    }

    fun startSession(
        distroName: String,
        user: String = "root",
        isolated: Boolean = false,
        rows: Int = 50,
        cols: Int = 200
    ): Session? {
        val prCli = File(prefixDir, "bin/pr-cli")
        if (!prCli.exists()) {
            Log.e(TAG, "pr-cli not found at $prCli")
            return null
        }

        val envVars = buildEnvVars()
        val args = arrayOf(prCli.absolutePath, "login", distroName, "--user", user)

        val spawned = PtyNative.spawn(args[0], args, envVars, rows, cols, null)
        val masterFd = spawned[0]
        if (masterFd < 0) {
            Log.e(TAG, "forkPty failed with fd=$masterFd")
            return null
        }

        Log.i(TAG, "PTY session started for $distroName, masterFd=$masterFd")
        return Session(masterFd, spawned[1])
    }

    /**
     * Start a PTY session with a pre-parsed argument list.
     * Use this instead of [runCommand] when arguments contain spaces or quotes
     * that would be mangled by naive string splitting.
     */
    fun startCustomSession(
        args: List<String>,
        rows: Int = 24,
        cols: Int = 80,
    ): Session? {
        require(args.isNotEmpty()) { "An executable is required" }
        require(args.all { '\u0000' !in it }) { "Arguments cannot contain NUL" }
        val envVars = buildEnvVars()
        val spawned = PtyNative.spawn(args[0], args.toTypedArray(), envVars, rows, cols, null)
        val masterFd = spawned[0]
        if (masterFd < 0) {
            Log.e(TAG, "forkPty failed with fd=$masterFd for ${args.joinToString(" ")}")
            return null
        }

        Log.i(TAG, "PTY custom session started: ${args.joinToString(" ")}, masterFd=$masterFd")
        return Session(masterFd, spawned[1])
    }

    fun runCommand(
        arguments: List<String>,
        rows: Int = 24,
        cols: Int = 80,
    ): Session? {
        return startCustomSession(PdnRuntime(host).command(arguments), rows, cols)
    }

    private fun buildEnvVars(): Array<String> {
        val environment = System.getenv().toMutableMap()
        environment.putAll(PdnRuntime(host).environment())
        return environment.flatMap { listOf(it.key, it.value) }.toTypedArray()
    }

    class Session @JvmOverloads constructor(val masterFd: Int, val pid: Int = -1) {
        var closed = false
            private set

        @Synchronized
        fun read(buf: ByteArray, offset: Int = 0, length: Int = buf.size): Int {
            if (closed) return -1
            val count = PtyNative.read(masterFd, buf, offset, length)
            if (count == 0) Thread.sleep(10)
            return count
        }

        @Synchronized
        fun write(data: ByteArray): Int {
            if (closed) return -1
            return PtyNative.write(masterFd, data, 0, data.size)
        }

        @Synchronized
        fun resize(rows: Int, cols: Int): Int {
            if (closed) return -1
            return PtyNative.resize(masterFd, rows, cols)
        }

        @Synchronized
        fun close() {
            if (!closed) {
                closed = true
                if (pid > 0) {
                    val initial = PtyNative.poll(pid)
                    if (initial[0] == 0) PtyNative.signal(pid, 15)
                    Thread {
                        val deadline = System.nanoTime() + 500_000_000L
                        var state = initial
                        while (state[0] == 0 && System.nanoTime() < deadline) {
                            Thread.sleep(10)
                            state = PtyNative.poll(pid)
                        }
                        if (state[0] == 0) {
                            PtyNative.signal(pid, 9)
                            while (PtyNative.poll(pid)[0] == 0) Thread.sleep(10)
                        }
                    }.apply { isDaemon = true }.start()
                }
                try {
                    PtyNative.close(masterFd)
                } catch (_: Exception) {}
            }
        }
    }
}
