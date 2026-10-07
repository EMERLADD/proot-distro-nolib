package id.or.oo.pr

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import id.or.oo.pr.engine.PdnRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val PDN_DISTROS = listOf(
    "alpine" to "Alpine",
    "ubuntu" to "Ubuntu",
    "debian" to "Debian",
    "arch" to "Arch Linux ARM",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdnScreen(app: App) {
    val context = LocalContext.current
    val runtime = remember(app) { PdnRuntime(app) }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(emptyList<DistroInfo>()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var output by remember { mutableStateOf(emptyList<String>()) }
    var showOutput by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<DistroInfo?>(null) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun refresh() {
        val installed = runtime.rootfsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.map { it.name } ?: emptyList()
        val builtins = PDN_DISTROS.map { (name, label) ->
            DistroInfo(name, label, name, installed.any { it.equals(name, ignoreCase = true) })
        }
        val extra = installed.filter { name -> PDN_DISTROS.none { it.first.equals(name, ignoreCase = true) } }
            .map { DistroInfo(it, it, it, true) }
        entries = builtins + extra
    }

    fun run(label: String, name: String, args: List<String>) {
        if (busy != null) return
        busy = name
        operation = label
        selected = name
        output = emptyList()
        showOutput = true
        scope.launch {
            try {
                runPdnCommand(runtime, args) { output = (output + it).takeLast(500) }
            } finally {
                busy = null
                refresh()
            }
        }
    }

    LaunchedEffect(runtime) {
        try {
            withContext(Dispatchers.IO) { runtime.prepare() }
            refresh()
        } catch (e: Exception) {
            output = listOf("ERROR: ${e.message}")
            operation = "Initialize"
            showOutput = true
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("PDN 工作区") },
            actions = {
                TextButton(enabled = busy == null, onClick = { run("检查引擎", "PDN", listOf("version")) }) {
                    Text("检查引擎")
                }
            },
        )
    }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Text("项目文件位于 Linux 的 /workspace，退出后仍会保留。", modifier = Modifier.padding(16.dp))
            }
            items(entries, key = { it.alias }) { distro ->
                DistroCard(
                    distro = distro,
                    isLoading = busy == distro.displayName,
                    onInstall = { run("Installing", distro.displayName, listOf("install", distro.alias)) },
                    onLogin = {
                        if (busy == null) {
                            context.startActivity(Intent(context, TerminalActivity::class.java).apply {
                                putExtra("distro", distro.alias)
                                putExtra("pdn", true)
                                val root = runtime.rootfsDir.listFiles()?.firstOrNull {
                                    it.isDirectory && it.name.equals(distro.alias, ignoreCase = true)
                                } ?: File(runtime.rootfsDir, distro.alias)
                                putExtra("rootfs", root.absolutePath)
                            })
                        }
                    },
                    onRemove = { if (busy == null) removing = distro },
                    onTest = {
                        val root = runtime.rootfsDir.listFiles()?.firstOrNull {
                            it.isDirectory && it.name.equals(distro.alias, ignoreCase = true)
                        } ?: File(runtime.rootfsDir, distro.alias)
                        run("Testing", distro.displayName, listOf(
                            "exec", "--rootfs", root.absolutePath,
                            "--bind", "${runtime.projectDir.absolutePath}:/workspace", "--work-dir", "/workspace",
                            "--", "/bin/sh", "-c",
                            "id -u; uname -r; pwd; printf '%s\\n' \"\$1\"; [ \"\$1\" = 'two words' ]",
                            "pdn-smoke", "two words",
                        ))
                    },
                )
            }
        }
    }

    removing?.let { distro ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("删除 ${distro.displayName}？") },
            text = { Text("将删除这个 Linux 系统中的全部数据。共享的项目目录会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    run("Removing", distro.displayName, listOf("remove", distro.alias, "--yes"))
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
        )
    }

    if (showOutput) {
        ModalBottomSheet(
            onDismissRequest = { if (busy == null) showOutput = false },
            sheetState = sheet,
        ) {
            OutputConsoleContent(operation, selected, busy != null, output) { showOutput = false }
        }
    }
}

private suspend fun runPdnCommand(runtime: PdnRuntime, args: List<String>, onLine: (String) -> Unit) {
    withContext(Dispatchers.IO) {
        try {
            val process = runtime.processBuilder(args).redirectErrorStream(true).start()
            process.outputStream.close()
            process.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    withContext(Dispatchers.Main) { onLine(line) }
                }
            }
            val status = process.waitFor()
            withContext(Dispatchers.Main) { onLine(if (status == 0) "Done." else "Exit code: $status") }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onLine("ERROR: ${e.message}") }
        }
    }
}
