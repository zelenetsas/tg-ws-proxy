package com.flowseal.tgwsproxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.flowseal.tgwsproxy.R
import com.flowseal.tgwsproxy.config.ConfigStore
import com.flowseal.tgwsproxy.config.ValidationResult
import com.flowseal.tgwsproxy.config.validateSettings
import com.flowseal.tgwsproxy.log.Log
import com.flowseal.tgwsproxy.proxy.DcPresets
import java.io.File

/**
 * Экран настроек — порт `ui/settings_form.py`.
 * Редактирует config.json; изменения применяются при перезапуске прокси.
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val store = remember { ConfigStore(File(context.applicationInfo.dataDir, "config.json")) }
    var cfg by remember { mutableStateOf(store.load()) }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = cfg.host,
            onValueChange = { cfg = cfg.copy(host = it); saved = false },
            label = { Text(stringResource(R.string.settings_host)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.host_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = cfg.port.toString(),
            onValueChange = { cfg = cfg.copy(port = it.toIntOrNull() ?: 0); saved = false },
            label = { Text(stringResource(R.string.settings_port)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = cfg.secret,
            onValueChange = { cfg = cfg.copy(secret = it); saved = false },
            label = { Text(stringResource(R.string.label_secret)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = cfg.dcIp.joinToString("\n"),
            onValueChange = {
                cfg = cfg.copy(dcIp = it.split('\n').toMutableList()); saved = false
            },
            label = { Text(stringResource(R.string.settings_dc_ip)) },
            modifier = Modifier.fillMaxWidth().height(120.dp),
        )
        // Пресеты DC: топ-жалоба оригинала — «не грузится медиа» из DC 1/3/5.
        // Пустой список = всё через цепочку CF-фолбэков, работает для любых DC.
        val preset = DcPresets.presetFor(cfg.dcIp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = preset == DcPresets.DEFAULT,
                onClick = {
                    cfg = cfg.copy(dcIp = DcPresets.DEFAULT_LINES.toMutableList())
                    saved = false
                },
                label = { Text(stringResource(R.string.dc_preset_default)) },
            )
            FilterChip(
                selected = preset == DcPresets.ALL_VIA_CF,
                onClick = {
                    cfg = cfg.copy(dcIp = DcPresets.ALL_VIA_CF_LINES.toMutableList())
                    saved = false
                },
                label = { Text(stringResource(R.string.dc_preset_all_cf)) },
            )
        }
        Text(
            stringResource(R.string.dc_media_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = cfg.bufKb.toString(),
            onValueChange = { cfg = cfg.copy(bufKb = it.toIntOrNull() ?: 256); saved = false },
            label = { Text(stringResource(R.string.settings_buf_kb)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = cfg.poolSize.toString(),
            onValueChange = { cfg = cfg.copy(poolSize = it.toIntOrNull() ?: 0); saved = false },
            label = { Text(stringResource(R.string.settings_pool_size)) },
            modifier = Modifier.fillMaxWidth(),
        )

        CheckRow(stringResource(R.string.settings_cfproxy), cfg.cfproxy) {
            cfg = cfg.copy(cfproxy = it); saved = false
        }
        CheckRow(stringResource(R.string.settings_cfproxy_user_enabled), cfg.cfproxyUserDomainEnabled) {
            cfg = cfg.copy(cfproxyUserDomainEnabled = it); saved = false
        }
        if (cfg.cfproxyUserDomainEnabled) {
            OutlinedTextField(
                value = cfg.cfproxyUserDomain.joinToString("\n"),
                onValueChange = {
                    cfg = cfg.copy(cfproxyUserDomain = it.split('\n').toMutableList()); saved = false
                },
                label = { Text(stringResource(R.string.settings_cfproxy_user_enabled)) },
                modifier = Modifier.fillMaxWidth().height(100.dp),
            )
        }
        CheckRow(stringResource(R.string.settings_cfworker_enabled), cfg.cfproxyWorkerEnabled) {
            cfg = cfg.copy(cfproxyWorkerEnabled = it); saved = false
        }
        if (cfg.cfproxyWorkerEnabled) {
            OutlinedTextField(
                value = cfg.cfproxyWorkerDomain.joinToString("\n"),
                onValueChange = {
                    cfg = cfg.copy(cfproxyWorkerDomain = it.split('\n').toMutableList()); saved = false
                },
                label = { Text(stringResource(R.string.settings_cfworker_enabled)) },
                modifier = Modifier.fillMaxWidth().height(100.dp),
            )
        }
        CheckRow(stringResource(R.string.settings_no_secure), cfg.noSecure) {
            cfg = cfg.copy(noSecure = it); saved = false
        }
        CheckRow(stringResource(R.string.settings_force_test_dc), cfg.forceTestDc) {
            cfg = cfg.copy(forceTestDc = it); saved = false
        }
        CheckRow(stringResource(R.string.settings_verbose), cfg.verbose) {
            cfg = cfg.copy(verbose = it); saved = false
        }
        CheckRow(stringResource(R.string.settings_check_updates), cfg.checkUpdates) {
            cfg = cfg.copy(checkUpdates = it); saved = false
        }

        error?.let { code ->
            Text(
                settingErrorText(code),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (saved) {
            Text(
                stringResource(R.string.settings_saved),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Button(
            onClick = {
                when (val result = validateSettings(cfg)) {
                    is ValidationResult.Error -> {
                        error = result.code
                        saved = false
                    }

                    is ValidationResult.Ok -> {
                        error = null
                        store.save(result.config)
                        cfg = result.config
                        saved = true
                        Log.info("Config saved")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_save))
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Checkbox(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun settingErrorText(code: String): String = when (code) {
    "bad_host" -> stringResource(R.string.validation_bad_host)
    "bad_port" -> stringResource(R.string.validation_bad_port)
    "dc_invalid" -> stringResource(R.string.validation_dc)
    "bad_secret_len" -> stringResource(R.string.validation_bad_secret_len)
    "bad_secret_hex" -> stringResource(R.string.validation_bad_secret_hex)
    else -> code
}
