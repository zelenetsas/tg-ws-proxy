package com.flowseal.tgwsproxy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.flowseal.tgwsproxy.proxy.Stats
import com.flowseal.tgwsproxy.service.ProxyLauncher
import com.flowseal.tgwsproxy.service.ProxyService
import com.flowseal.tgwsproxy.service.ProxyState
import com.flowseal.tgwsproxy.ui.LogsScreen
import com.flowseal.tgwsproxy.ui.ReliabilityScreen
import com.flowseal.tgwsproxy.ui.SettingsScreen
import com.flowseal.tgwsproxy.ui.StatsCards
import com.flowseal.tgwsproxy.ui.StatusCard
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { MainScreen() }
        }
    }
}

@Composable
fun MainScreen() {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.tab_status),
        stringResource(R.string.tab_settings),
        stringResource(R.string.tab_logs),
        stringResource(R.string.tab_reliability),
    )
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val state by ProxyService.state.collectAsState()
    var updateInfo by remember { mutableStateOf<Pair<String, String>?>(null) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }

            when (tab) {
                0 -> StatusTab(
                    state = state,
                    onStart = { ProxyLauncher.send(context, ProxyService.ACTION_START) },
                    onStop = {
                        ProxyLauncher.send(context, ProxyService.ACTION_STOP)
                    },
                    onCopy = { text ->
                        val cm =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("tg-ws-proxy", text))
                        scope.launch {
                            snackbar.showSnackbar(context.getString(R.string.link_copied))
                        }
                    },
                    onShare = { text ->
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    },
                    onOpenTelegram = { link ->
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                        } catch (e: Exception) {
                            scope.launch {
                                snackbar.showSnackbar(context.getString(R.string.link_copied))
                            }
                        }
                    },
                )

                1 -> SettingsScreen()
                2 -> LogsScreen()
                3 -> ReliabilityScreen()
            }
        }
    }

    updateInfo?.let { (latest, current) ->
        AlertDialog(
            onDismissRequest = { updateInfo = null },
            title = { Text(stringResource(R.string.update_dialog_title)) },
            text = { Text(stringResource(R.string.update_dialog_text, latest, current)) },
            confirmButton = {
                TextButton(onClick = {
                    updateInfo = null
                    try {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/Flowseal/tg-ws-proxy/releases/latest"),
                            ),
                        )
                    } catch (_: Exception) {
                    }
                }) { Text(stringResource(R.string.update_open)) }
            },
            dismissButton = {
                TextButton(onClick = { updateInfo = null }) {
                    Text(stringResource(R.string.update_later))
                }
            },
        )
    }
}

@Composable
fun StatusTab(
    state: ProxyState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onOpenTelegram: (String) -> Unit,
) {
    val stats by Stats.flow.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(state, onCopy, onShare, onOpenTelegram, onStart, onStop)
        StatsCards(stats)
    }
}
