package com.flowseal.tgwsproxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.flowseal.tgwsproxy.R
import com.flowseal.tgwsproxy.proxy.Protocol
import com.flowseal.tgwsproxy.proxy.StatsSnapshot
import com.flowseal.tgwsproxy.service.ProxyState

/** Карточка статуса: состояние, адрес, ссылки, кнопки управления. */
@Composable
fun StatusCard(
    state: ProxyState,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onOpenTelegram: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val (statusText, isError) = when (state) {
                is ProxyState.Running -> stringResource(R.string.status_running) to false
                is ProxyState.Starting -> stringResource(R.string.btn_start) to false
                is ProxyState.Failed ->
                    stringResource(R.string.status_error, state.message) to true
                ProxyState.Stopped -> stringResource(R.string.status_stopped) to false
            }
            Text(
                statusText,
                style = MaterialTheme.typography.titleMedium,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )

            if (state is ProxyState.Running) {
                Text(
                    state.link,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onOpenTelegram(state.link) }) {
                        Text(stringResource(R.string.btn_open_telegram))
                    }
                    OutlinedButton(onClick = { onCopy(state.link) }) {
                        Text(stringResource(R.string.btn_copy_link))
                    }
                }
                val hint = stringResource(R.string.hint_first_run)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onShare(state.link) }) {
                        Text(stringResource(R.string.btn_share_link))
                    }
                    OutlinedButton(
                        onClick = { onShare(hint) },
                    ) {
                        Text(stringResource(R.string.action_open))
                    }
                }
                // QR-код ссылки — навести камеру Telegram на другом устройстве
                QrCodeCard(link = state.link, onClick = { onShare(state.link) })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val running = state is ProxyState.Running || state is ProxyState.Starting
                if (running) {
                    Button(onClick = onStop) { Text(stringResource(R.string.btn_stop)) }
                } else {
                    Button(onClick = onStart) { Text(stringResource(R.string.btn_start)) }
                }
            }

            Text(
                stringResource(R.string.hint_first_run),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Сетка статистики — порт stats.py в UI. */
@Composable
fun StatsCards(stats: StatsSnapshot) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.label_stats),
                style = MaterialTheme.typography.titleMedium,
            )
            StatRow(stringResource(R.string.stats_total), stats.connectionsTotal.toString())
            StatRow(stringResource(R.string.stats_active), stats.connectionsActive.toString())
            StatRow(stringResource(R.string.stats_up), Protocol.humanBytes(stats.bytesUp))
            StatRow(stringResource(R.string.stats_down), Protocol.humanBytes(stats.bytesDown))
            StatRow(stringResource(R.string.stats_bad), stats.connectionsBad.toString())
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
