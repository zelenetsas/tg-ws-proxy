package com.flowseal.tgwsproxy.ui

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.flowseal.tgwsproxy.R
import com.flowseal.tgwsproxy.config.ConfigStore
import com.flowseal.tgwsproxy.log.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Карточка статуса с индикатором ok/проблема и кнопками действий. */
@Composable
private fun ReliabilityCard(
    title: String,
    ok: Boolean,
    okText: String,
    badText: String,
    actions: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                if (ok) okText else badText,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            actions()
        }
    }
}

/** Системный диалог «Не оптимизировать» (с fallback на список приложений). */
private fun requestBatteryExemption(context: Context) {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    if (!openSafely(context, direct)) {
        openSafely(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

/**
 * Автозапуск HyperOS/MIUI. Известные компоненты Xiaomi пробуются по очереди,
 * fallback — страница приложения в системных настройках.
 */
private fun openMiuiAutostart(context: Context) {
    val candidates = listOf(
        ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
        ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartDetailActivity",
        ),
        ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
    )
    for (component in candidates) {
        val intent = Intent().setComponent(component)
        intent.putExtra("package_name", context.packageName)
        intent.putExtra("package_label", context.getString(R.string.app_name))
        if (openSafely(context, intent)) return
    }
    openSafely(
        context,
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ),
    )
}

private fun openSafely(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: Exception) {
    false
}

/**
 * Экран «Надёжность фона»: статус исключений энергосбережения, автозапуск
 * и переходы к специфичным настройкам HyperOS/MIUI (автозапуск Xiaomi,
 * экономия батареи, точные будильники).
 */
@Composable
fun ReliabilityScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember {
        ConfigStore(File(context.applicationInfo.dataDir, "config.json"))
    }

    var autostart by remember { mutableStateOf(true) }
    var batteryExempt by remember { mutableStateOf(false) }
    var exactAlarm by remember { mutableStateOf(true) }

    fun refresh() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        batteryExempt = pm.isIgnoringBatteryOptimizations(context.packageName)
        exactAlarm = if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                    .canScheduleExactAlarms()
            } catch (e: Exception) {
                true
            }
        } else {
            true
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { autostart = store.load().autostart }
        refresh()
    }

    // Обновляем статус при возврате из системных настроек
    val lifecycleOwner = context as? LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReliabilityCard(
            title = stringResource(R.string.rel_battery_title),
            ok = batteryExempt,
            okText = stringResource(R.string.rel_battery_ok),
            badText = stringResource(R.string.rel_battery_bad),
        ) {
            Button(
                onClick = { requestBatteryExemption(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.rel_battery_btn))
            }
            OutlinedButton(
                onClick = {
                    openSafely(
                        context,
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.rel_battery_list_btn))
            }
        }

        ReliabilityCard(
            title = stringResource(R.string.rel_autostart_title),
            ok = autostart,
            okText = stringResource(R.string.rel_autostart_ok),
            badText = stringResource(R.string.rel_autostart_bad),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.rel_autostart_check))
                Checkbox(
                    checked = autostart,
                    onCheckedChange = { value ->
                        autostart = value
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val cfg = store.load()
                                cfg.autostart = value
                                store.save(cfg)
                                Log.info("autostart = $value")
                            }
                        }
                    },
                )
            }
            OutlinedButton(
                onClick = { openMiuiAutostart(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.rel_miui_autostart_btn))
            }
        }

        ReliabilityCard(
            title = stringResource(R.string.rel_alarm_title),
            ok = exactAlarm,
            okText = stringResource(R.string.rel_alarm_ok),
            badText = stringResource(R.string.rel_alarm_bad),
        ) {
            OutlinedButton(
                onClick = {
                    openSafely(
                        context,
                        Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.rel_alarm_btn))
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.rel_xiaomi_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.rel_xiaomi_steps),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
