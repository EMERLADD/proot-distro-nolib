package id.or.oo.pr.engine

import java.io.File

interface ProotHost {
    val nativeLibDir: File
    val prefixDir: File
    val homeDir: File
    val packageName: String
    val cacheDir: File
}
