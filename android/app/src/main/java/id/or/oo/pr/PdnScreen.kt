package id.or.oo.pr

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import id.or.oo.pr.engine.AlpinePackages
import id.or.oo.pr.engine.PdnRuntime
import id.or.oo.pr.engine.PdnEvent
import id.or.oo.pr.engine.PdnListener
import id.or.oo.pr.engine.PdnOperations
import id.or.oo.pr.engine.PdnHostException
import id.or.oo.pr.engine.PdnResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream

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
    val keyboard = LocalSoftwareKeyboardController.current
    val runtime = remember(app) { PdnRuntime(app) }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(emptyList<DistroInfo>()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var output by remember { mutableStateOf(emptyList<String>()) }
    var showOutput by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf("") }
    var lastStatus by remember { mutableStateOf<Int?>(null) }
    var phase by remember { mutableStateOf("准备中") }
    var progress by remember { mutableStateOf<Int?>(null) }
    var result by remember { mutableStateOf<PdnResult?>(null) }
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

    fun run(label: String, name: String, createBuilder: () -> ProcessBuilder) {
        if (busy != null) return
        keyboard?.hide()
        busy = name
        operation = label
        selected = name
        output = emptyList()
        lastStatus = null
        result = null
        progress = null
        phase = "准备中"
        showOutput = true
        scope.launch {
            try {
                result = runPdnCommand(runtime, createBuilder,
                    onLine = { output = (output + it).takeLast(500) },
                    onEvent = { event ->
                        if (event.type == "stage") {
                            phase = pdnStageLabel(event.stage)
                            progress = null
                        } else if (event.type == "progress") {
                            progress = event.percent
                        }
                    },
                )
                lastStatus = result?.let { if (it.isSuccess) 0 else if (it.exitCode == 0) -1 else it.exitCode } ?: -1
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
            output = listOf("错误：${e.message}")
            lastStatus = -1
            operation = "初始化"
            showOutput = true
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("PDN 工作区") },
            actions = {
                TextButton(enabled = busy == null, onClick = { run("检查引擎", "PDN") { runtime.version() } }) {
                    Text("检查引擎")
                }
            },
        )
    }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            item {
                Text("项目文件位于 Linux 的 /workspace，退出后仍会保留。", modifier = Modifier.padding(16.dp))
            }
            items(entries, key = { it.alias }) { distro ->
                DistroCard(
                    distro = distro,
                    isLoading = busy == distro.displayName,
                    onInstall = { run("安装系统", distro.displayName) { runtime.install(distro.alias) } },
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
                        run("检查系统", distro.displayName) { runtime.exec(root, listOf(
                            "/bin/sh", "-c",
                            "id -u; uname -r; pwd; printf '%s\\n' \"\$1\"; [ \"\$1\" = 'two words' ]",
                            "pdn-smoke", "two words",
                        )) }
                    },
                )
                if (distro.alias.equals("alpine", ignoreCase = true)) {
                    val root = runtime.rootfsDir.listFiles()?.firstOrNull {
                        it.isDirectory && it.name.equals(distro.alias, ignoreCase = true)
                    } ?: File(runtime.rootfsDir, distro.alias)
                    val packages = AlpinePackages(runtime, root)
                    AlpinePackagePanel(
                        installed = distro.isInstalled,
                        enabled = busy == null,
                        status = if (selected == distro.displayName) lastStatus else null,
                        onInstall = { input -> run("安装软件", distro.displayName) { packages.install(input) } },
                        onUpdate = { run("更新软件索引", distro.displayName) { packages.update() } },
                        onInstalled = { run("已安装软件", distro.displayName) { packages.installed() } },
                    )
                }
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
                    run("删除系统", distro.displayName) { runtime.remove(distro.alias) }
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
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                if (busy != null) {
                    Text(phase + (progress?.let { " · $it%" } ?: ""))
                    val percent = progress
                    if (percent == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                }
                result?.takeUnless { it.isSuccess }?.let { failure ->
                    Text(when (failure.outcome) {
                        "guest_exit" -> failure.guestSignal?.let { "Linux 命令被信号 $it 终止" }
                            ?: "Linux 命令失败，退出码：${failure.guestExitCode ?: failure.exitCode}"
                        "cancelled" -> "操作已中断"
                        "host_protocol_error" -> "无法确认操作结果：事件通道异常"
                        else -> "PDN 操作失败"
                    }, color = MaterialTheme.colorScheme.error)
                    failure.code?.let { Text("错误类型：$it") }
                    failure.message?.let { Text(it) }
                    failure.suggestion?.let { Text("建议：$it") }
                    Spacer(Modifier.height(12.dp))
                }
            }
            OutputConsoleContent(operation, selected, busy != null, output, lastStatus) { showOutput = false }
        }
    }
}

@Composable
private fun AlpinePackagePanel(
    installed: Boolean,
    enabled: Boolean,
    status: Int?,
    onInstall: (String) -> Unit,
    onUpdate: () -> Unit,
    onInstalled: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val error = if (input.isBlank()) null else runCatching { AlpinePackages.packageNames(input) }
        .exceptionOrNull()?.message
    ElevatedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Alpine 软件", style = MaterialTheme.typography.titleMedium)
            if (!installed) {
                Text("先安装 Alpine，即可在这里安装 Linux 软件。")
            } else {
                Text("输入软件包名或点选常用软件，安装前会自动更新索引。")
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("软件包名") },
                    placeholder = { Text("例如 git curl python3") },
                    supportingText = { Text(error ?: "多个软件包用空格分隔") },
                    isError = error != null,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (name in listOf("git", "curl", "python3", "nodejs", "npm", "build-base")) {
                        AssistChip(
                            onClick = { input = (input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() } + name).distinct().joinToString(" ") },
                            label = { Text(name) },
                            enabled = enabled,
                        )
                    }
                }
                Button(
                    onClick = { onInstall(input) },
                    enabled = enabled && input.isNotBlank() && error == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("安装软件") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onUpdate, enabled = enabled, modifier = Modifier.weight(1f)) {
                        Text("更新索引")
                    }
                    OutlinedButton(onClick = onInstalled, enabled = enabled, modifier = Modifier.weight(1f)) {
                        Text("已安装软件")
                    }
                }
                if (!enabled) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("正在执行，请等待…")
                } else if (status != null) {
                    Text(
                        if (status == 0) "操作成功，可查看已安装软件确认。" else "操作失败，请查看日志中的原因。",
                        color = if (status == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private fun pdnStageLabel(stage: String?): String = when (stage) {
    "preparing" -> "检查目录和参数"
    "copying" -> "复制本地安装包"
    "downloading" -> "下载系统"
    "verifying" -> "校验安装包"
    "extracting" -> "解压系统"
    "configuring" -> "配置系统"
    "initializing" -> "初始化系统"
    "publishing" -> "保存安装结果"
    "backing_up" -> "备份系统"
    "restoring" -> "恢复系统"
    "starting" -> "启动 Linux 命令"
    "running" -> "Linux 命令正在运行"
    else -> stage ?: "执行中"
}

private class PdnLogLines(private val onLine: (String) -> Unit) {
    private val pending = ByteArrayOutputStream()
    fun accept(data: ByteArray) {
        for (byte in data) {
            if (byte == '\n'.code.toByte()) flush()
            else {
                pending.write(byte.toInt())
                if (pending.size() >= 16384) flush()
            }
        }
    }
    fun flush() {
        val line = pending.toString("UTF-8").trimEnd('\r')
        pending.reset()
        onLine(line)
    }
    fun finish() { if (pending.size() > 0) flush() }
}

private suspend fun runPdnCommand(
    runtime: PdnRuntime,
    createBuilder: () -> ProcessBuilder,
    onLine: (String) -> Unit,
    onEvent: (PdnEvent) -> Unit,
): PdnResult? = runInterruptible(Dispatchers.IO) {
    fun line(text: String) = runBlocking(Dispatchers.Main) { onLine(text) }
    val stdout = PdnLogLines(::line)
    val stderr = PdnLogLines(::line)
    try {
        val result = PdnOperations(runtime).run(createBuilder(), object : PdnListener {
            override fun onStdout(data: ByteArray) { stdout.accept(data) }
            override fun onStderr(data: ByteArray) { stderr.accept(data) }
            override fun onEvent(event: PdnEvent) = runBlocking(Dispatchers.Main) { onEvent(event) }
        })
        stdout.finish()
        stderr.finish()
        line(if (result.isSuccess) "操作成功。" else "操作失败，退出码：${result.exitCode}")
        result
    } catch (e: CancellationException) {
        throw e
    } catch (e: InterruptedException) {
        throw e
    } catch (e: PdnHostException) {
        stdout.finish()
        stderr.finish()
        line("宿主错误类型：${e.code}")
        line("错误：${e.message}")
        line("建议：${e.suggestion}")
        null
    } catch (e: Exception) {
        stdout.finish()
        stderr.finish()
        line("错误：${e.message}")
        null
    }
}
