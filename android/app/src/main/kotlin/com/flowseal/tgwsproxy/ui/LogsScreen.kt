package com.flowseal.tgwsproxy.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowseal.tgwsproxy.R
import com.flowseal.tgwsproxy.log.LogFile
import java.io.File
import kotlinx.coroutines.delay

/**
 * Экран логов: хвост файла, шеринг и очистка.
 * Аналог пункта «Открыть логи» десктопной версии.
 */
@Composable
fun LogsScreen() {
    val context = LocalContext.current
    val logFile = remember {
        LogFile(File(context.applicationInfo.dataDir, "files/proxy.log"))
    }
    var text by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            text = logFile.tail(400)
            delay(1000)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, logFile.readAll())
                }
                context.startActivity(Intent.createChooser(send, null))
            }) {
                Text(stringResource(R.string.logs_share))
            }
            OutlinedButton(onClick = {
                logFile.clear()
                text = ""
            }) {
                Text(stringResource(R.string.logs_clear))
            }
        }

        Card(Modifier.fillMaxWidth()) {
            if (text.isEmpty()) {
                Text(
                    stringResource(R.string.logs_empty),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    text,
                    Modifier.padding(12.dp).fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    ),
                )
            }
        }
    }
}
