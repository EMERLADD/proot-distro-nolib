package id.or.oo.pr.engine

import java.io.File

class AlpinePackages(private val runtime: PdnRuntime, private val rootfs: File) {
    companion object {
        fun packageNames(input: String): List<String> {
            val names = input.trim().split(Regex("\\s+")).distinct()
            require(names.all { Regex("[A-Za-z0-9][A-Za-z0-9+_.-]*").matches(it) }) {
                "请输入软件包名，用空格分隔，例如 git curl；无需输入 apk 命令"
            }
            return names
        }
    }

    fun install(input: String): ProcessBuilder {
        val names = packageNames(input)
        return runtime.exec(rootfs, listOf(
            "/bin/sh", "-c", "apk update && exec apk add -- \"\$@\"", "pdn-apk",
        ) + names)
    }

    fun update(): ProcessBuilder = runtime.exec(rootfs, listOf("/bin/sh", "-c", "exec apk update"))

    fun installed(): ProcessBuilder = runtime.exec(rootfs, listOf("/bin/sh", "-c", "exec apk info"))
}
