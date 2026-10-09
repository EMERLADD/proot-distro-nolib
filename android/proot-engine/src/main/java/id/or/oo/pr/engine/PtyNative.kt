package id.or.oo.pr.engine

object PtyNative {
    init {
        System.loadLibrary("ptyjni")
    }

    fun forkPty(cmd: String, args: Array<String>?, envVars: Array<String>?, rows: Int, cols: Int): Int {
        return nativeForkPty(cmd, args, envVars, rows, cols)
    }

    fun read(fd: Int, buf: ByteArray, offset: Int, length: Int): Int {
        return nativeRead(fd, buf, offset, length).coerceAtLeast(-1)
    }

    fun write(fd: Int, buf: ByteArray, offset: Int, length: Int): Int {
        return nativeWrite(fd, buf, offset, length).coerceAtLeast(-1)
    }

    fun resize(fd: Int, rows: Int, cols: Int): Int {
        return nativeResize(fd, rows, cols).coerceAtLeast(-1)
    }

    fun waitPid(pid: Int): Int {
        return nativeWaitPid(pid)
    }

    fun close(fd: Int) {
        nativeClose(fd)
    }

    fun getPid(): Int {
        return nativeGetPid()
    }

    @JvmStatic
    fun readSession(fd: Int, buf: ByteArray, offset: Int, length: Int): Int = nativeRead(fd, buf, offset, length)

    @JvmStatic
    fun writeSession(fd: Int, buf: ByteArray, offset: Int, length: Int): Int = nativeWrite(fd, buf, offset, length)

    @JvmStatic
    fun resizeSession(fd: Int, rows: Int, cols: Int): Int = nativeResize(fd, rows, cols)

    @JvmStatic
    fun spawn(cmd: String, args: Array<String>, envVars: Array<String>, rows: Int, cols: Int, directory: String?): IntArray =
        nativeSpawn(cmd, args, envVars, rows, cols, directory)

    @JvmStatic
    fun poll(pid: Int): IntArray = nativePoll(pid)

    @JvmStatic
    fun signal(pid: Int, signal: Int): Int = nativeSignal(pid, signal)

    private external fun nativeDumpCoverage(path: String): Int
    private external fun nativeSpawn(cmd: String, args: Array<String>, envVars: Array<String>, rows: Int, cols: Int, directory: String?): IntArray
    private external fun nativePoll(pid: Int): IntArray
    private external fun nativeSignal(pid: Int, signal: Int): Int
    private external fun nativeForkPty(cmd: String, args: Array<String>?, envVars: Array<String>?, rows: Int, cols: Int): Int
    private external fun nativeRead(fd: Int, buf: ByteArray, offset: Int, length: Int): Int
    private external fun nativeWrite(fd: Int, buf: ByteArray, offset: Int, length: Int): Int
    private external fun nativeResize(fd: Int, rows: Int, cols: Int): Int
    private external fun nativeWaitPid(pid: Int): Int
    private external fun nativeClose(fd: Int)
    private external fun nativeGetPid(): Int
}
