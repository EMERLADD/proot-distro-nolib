package id.or.oo.pr

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

data class DistroInfo(
    val alias: String,
    val displayName: String,
    val installSource: String,
    val isInstalled: Boolean,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: Compose owns all insets via imePadding() / navigationBarsPadding().
        // Manifest uses adjustNothing so the window never auto-resizes for the keyboard.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val app = application as App
        setContent {
            val darkTheme = isSystemInDarkTheme()
            val colorScheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    val context = this
                    if (darkTheme) dynamicDarkColorScheme(context)
                    else dynamicLightColorScheme(context)
                }
                darkTheme -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PdnScreen(app)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DistroCard(
    distro: DistroInfo,
    isLoading: Boolean,
    onInstall: () -> Unit,
    onLogin: () -> Unit,
    onRemove: () -> Unit,
    onTest: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Name + status
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = distro.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (distro.isInstalled) "● Installed" else "○ Not installed",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (distro.isInstalled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.outline
                )
            }

            // Actions
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else if (distro.isInstalled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Primary action: login
                    FilledIconButton(onClick = onLogin) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Login")
                    }
                    // Secondary actions: test + remove in overflow menu
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Test") },
                                leadingIcon = {
                                    Icon(Icons.Default.BugReport, contentDescription = null)
                                },
                                onClick = {
                                    showMenu = false
                                    onTest()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Remove",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onRemove()
                                }
                            )
                        }
                    }
                }
            } else {
                OutlinedButton(
                    onClick = onInstall,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Install")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Output console (rendered inside ModalBottomSheet)
// ---------------------------------------------------------------------------

@Composable
fun OutputConsoleContent(
    operationLabel: String,
    distroName: String,
    isRunning: Boolean,
    outputLines: List<String>,
    onClose: () -> Unit,
) {
    val scrollState = rememberScrollState()
    // Auto-scroll to latest output as new lines arrive
    LaunchedEffect(outputLines.size) {
        scrollState.animateScrollTo(Int.MAX_VALUE)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .navigationBarsPadding()
    ) {
        // Header: operation label + progress
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "$operationLabel: $distroName",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                if (!isRunning) {
                    Text(
                        "Completed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Scrollable output area with monospace text
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp, max = 400.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp,
        ) {
            SelectionContainer(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .padding(12.dp)
            ) {
                Text(
                    text = outputLines.joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Close button — disabled while the operation is still running
        Button(
            onClick = onClose,
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isRunning) "Running…" else "Close")
        }

        Spacer(Modifier.height(8.dp))
    }
}
