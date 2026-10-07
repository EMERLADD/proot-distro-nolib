package id.or.oo.pr

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import org.connectbot.terminal.Terminal
import id.or.oo.pr.engine.ProotLauncher
import java.io.File
import kotlin.concurrent.thread

class TerminalActivity : ComponentActivity() {

    companion object {
        private const val TAG = "PR"
    }

    private var session: ProotLauncher.Session? = null
    private var emulator: TerminalEmulator? = null
    private var readerThread: Thread? = null
    private var sessionStarted = false
    private var terminalRows by mutableIntStateOf(24)
    private var terminalColumns by mutableIntStateOf(80)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val distroName = intent.getStringExtra("distro") ?: run {
            finish()
            return
        }

        val app = application as App
        val launcher = ProotLauncher(app)

        val em = TerminalEmulatorFactory.create(
            initialRows = 1,
            initialCols = 1,
            defaultForeground = Color.White,
            defaultBackground = Color(0xFF1a1a2e),
            onKeyboardInput = { data ->
                session?.write(data)
            },
            onResize = { dims ->
                terminalRows = dims.rows
                terminalColumns = dims.columns
                Log.d(TAG, "Terminal resized to ${dims.rows}x${dims.columns}")
                if (!isFinishing && !isDestroyed) {
                    if (!sessionStarted) {
                        sessionStarted = true
                        launchTerminal(launcher, distroName, dims.rows, dims.columns)
                    } else {
                        session?.resize(dims.rows, dims.columns)
                    }
                }
            }
        )
        emulator = em

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cleanup()
                finish()
            }
        })

        val terminalPreferences = getSharedPreferences("terminal_ui", MODE_PRIVATE)
        setContent {
            var fontSize by rememberSaveable {
                mutableIntStateOf(terminalPreferences.getInt("font_size", 12).coerceIn(6, 30))
            }
            var showKeyboard by rememberSaveable { mutableStateOf(true) }

            fun setFontSize(value: Int) {
                fontSize = value.coerceIn(6, 30)
                terminalPreferences.edit().putInt("font_size", fontSize).apply()
            }

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF1a1a2e)
                ) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "$terminalColumns × $terminalRows",
                                modifier = Modifier.weight(1f),
                                color = Color.White,
                                fontSize = 12.sp,
                            )
                            TextButton(enabled = fontSize > 6, onClick = { setFontSize(fontSize - 1) }) {
                                Text("A−", color = if (fontSize > 6) Color.White else Color.Gray)
                            }
                            TextButton(onClick = { setFontSize(12) }) {
                                Text("${fontSize}sp", color = Color.White)
                            }
                            TextButton(enabled = fontSize < 30, onClick = { setFontSize(fontSize + 1) }) {
                                Text("A+", color = if (fontSize < 30) Color.White else Color.Gray)
                            }
                            TextButton(onClick = { showKeyboard = !showKeyboard }) {
                                Text(if (showKeyboard) "收起键盘" else "显示键盘", color = Color.White)
                            }
                        }
                        Terminal(
                            terminalEmulator = em,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            initialFontSize = fontSize.sp,
                            backgroundColor = Color(0xFF1a1a2e),
                            foregroundColor = Color.White,
                            keyboardEnabled = true,
                            showSoftKeyboard = showKeyboard,
                        )
                    }
                }
            }
        }
    }

    private fun launchTerminal(launcher: ProotLauncher, distroName: String, rows: Int, columns: Int) {
        val em = emulator ?: return
        val sess = if (intent.getBooleanExtra("pdn", false)) {
            val rootfs = intent.getStringExtra("rootfs") ?: run {
                finish()
                return
            }
            launcher.startPdnSession(File(rootfs), rows = rows, cols = columns)
        } else {
            launcher.startSession(distroName, rows = rows, cols = columns)
        }
        if (sess == null) {
            Log.e(TAG, "Failed to start session for $distroName")
            finish()
            return
        }
        session = sess

        readerThread = thread(name = "pty-reader") {
            val buf = ByteArray(8192)
            while (!sess.closed) {
                try {
                    val n = sess.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        em.writeInput(buf, 0, n)
                    }
                } catch (e: Exception) {
                    if (!sess.closed) Log.e(TAG, "PTY read error", e)
                    break
                }
            }
            Log.i(TAG, "PTY reader thread exited")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    private fun cleanup() {
        session?.close()
        session = null
        readerThread?.interrupt()
        readerThread = null
    }
}
