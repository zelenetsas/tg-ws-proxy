package com.flowseal.tgwsproxy.config

import com.flowseal.tgwsproxy.log.Log
import com.flowseal.tgwsproxy.proxy.ProxyConfig
import com.flowseal.tgwsproxy.proxy.isValidIpv4
import com.flowseal.tgwsproxy.proxy.parseDcIpList
import com.flowseal.tgwsproxy.proxy.toHexLower
import com.flowseal.tgwsproxy.proxy.urandom
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Порт `utils/default_config.py` + сериализация config.json
 * (порт `utils/tray_common.py`).
 */
class ConfigStore(private val file: File) {

    /** Настройки приложения — порт `_TRAY_DEFAULTS_COMMON`. */
    data class AppConfig(
        var port: Int = 1443,
        var host: String = "127.0.0.1",
        var dcIp: MutableList<String> = mutableListOf(
            "2:149.154.167.220",
            "4:149.154.167.220",
        ),
        var verbose: Boolean = false,
        var checkUpdates: Boolean = true,
        var logMaxMb: Double = 5.0,
        var bufKb: Int = 256,
        var poolSize: Int = 4,
        var cfproxy: Boolean = true,
        var h2: Boolean = true,
        var cfproxyUserDomainEnabled: Boolean = false,
        var cfproxyUserDomain: MutableList<String> = mutableListOf(),
        var cfproxyWorkerEnabled: Boolean = false,
        var cfproxyWorkerDomain: MutableList<String> = mutableListOf(),
        var forceTestDc: Boolean = false,
        var noSecure: Boolean = false,
        var appearance: String = "auto",
        var language: String = "ru",
        var autostart: Boolean = true,
        var secret: String = urandom(16).toHexLower(),
    )

    private fun jsonArrayToList(arr: JSONArray?): MutableList<String>? =
        arr?.let { MutableList(it.length()) { i -> it.getString(i) } }

    fun load(): AppConfig {
        val cfg = AppConfig()
        if (!file.exists()) return cfg
        return try {
            val obj = JSONObject(file.readText())
            cfg.port = obj.optInt("port", cfg.port)
            cfg.host = obj.optString("host", cfg.host)
            jsonArrayToList(obj.optJSONArray("dc_ip"))?.let { cfg.dcIp = it }
            cfg.verbose = obj.optBoolean("verbose", cfg.verbose)
            cfg.checkUpdates = obj.optBoolean("check_updates", cfg.checkUpdates)
            cfg.logMaxMb = obj.optDouble("log_max_mb", cfg.logMaxMb)
            cfg.bufKb = obj.optInt("buf_kb", cfg.bufKb)
            cfg.poolSize = obj.optInt("pool_size", cfg.poolSize)
            cfg.cfproxy = obj.optBoolean("cfproxy", cfg.cfproxy)
            cfg.h2 = obj.optBoolean("h2", cfg.h2)
            cfg.cfproxyUserDomainEnabled =
                obj.optBoolean("cfproxy_user_domain_enabled", cfg.cfproxyUserDomainEnabled)
            jsonArrayToList(obj.optJSONArray("cfproxy_user_domain"))?.let { cfg.cfproxyUserDomain = it }
            cfg.cfproxyWorkerEnabled =
                obj.optBoolean("cfproxy_worker_enabled", cfg.cfproxyWorkerEnabled)
            jsonArrayToList(obj.optJSONArray("cfproxy_worker_domain"))?.let { cfg.cfproxyWorkerDomain = it }
            cfg.forceTestDc = obj.optBoolean("force_test_dc", cfg.forceTestDc)
            cfg.noSecure = obj.optBoolean("no_secure", cfg.noSecure)
            cfg.appearance = obj.optString("appearance", cfg.appearance)
            cfg.language = obj.optString("language", cfg.language)
            cfg.autostart = obj.optBoolean("autostart", cfg.autostart)
            cfg.secret = obj.optString("secret", cfg.secret)
            cfg
        } catch (e: Exception) {
            Log.error("Failed to load config, using defaults", e)
            cfg
        }
    }

    fun save(cfg: AppConfig) {
        try {
            file.parentFile?.mkdirs()
            val obj = JSONObject()
            obj.put("port", cfg.port)
            obj.put("host", cfg.host)
            obj.put("dc_ip", JSONArray(cfg.dcIp))
            obj.put("verbose", cfg.verbose)
            obj.put("check_updates", cfg.checkUpdates)
            obj.put("log_max_mb", cfg.logMaxMb)
            obj.put("buf_kb", cfg.bufKb)
            obj.put("pool_size", cfg.poolSize)
            obj.put("cfproxy", cfg.cfproxy)
            obj.put("h2", cfg.h2)
            obj.put("cfproxy_user_domain_enabled", cfg.cfproxyUserDomainEnabled)
            obj.put("cfproxy_user_domain", JSONArray(cfg.cfproxyUserDomain))
            obj.put("cfproxy_worker_enabled", cfg.cfproxyWorkerEnabled)
            obj.put("cfproxy_worker_domain", JSONArray(cfg.cfproxyWorkerDomain))
            obj.put("force_test_dc", cfg.forceTestDc)
            obj.put("no_secure", cfg.noSecure)
            obj.put("appearance", cfg.appearance)
            obj.put("language", cfg.language)
            obj.put("autostart", cfg.autostart)
            obj.put("secret", cfg.secret)
            file.writeText(obj.toString(2))
        } catch (e: Exception) {
            Log.error("Failed to save config", e)
        }
    }

    /** Преобразование в [ProxyConfig] — порт присвоений в `tray_common`. */
    fun toProxyConfig(cfg: AppConfig): ProxyConfig {
        val redirects = try {
            parseDcIpList(cfg.dcIp)
        } catch (e: Exception) {
            Log.warn("dc_ip invalid, using defaults: ${e.message}")
            ProxyConfig.DEFAULT_DC_REDIRECTS
        }
        return ProxyConfig(
            port = cfg.port,
            host = cfg.host,
            secret = cfg.secret,
            dcRedirects = redirects,
            bufferSize = maxOf(4, cfg.bufKb) * 1024,
            poolSize = maxOf(0, cfg.poolSize),
            fallbackCfproxy = cfg.cfproxy,
            cfproxyUserDomains = if (cfg.cfproxyUserDomainEnabled) cfg.cfproxyUserDomain else emptyList(),
            cfproxyWorkerDomains = if (cfg.cfproxyWorkerEnabled) cfg.cfproxyWorkerDomain else emptyList(),
            cfproxyH2Media = cfg.h2,
            disableSecure = cfg.noSecure,
            forceTestDc = cfg.forceTestDc,
        )
    }
}

/** Порт `ui/settings.py::validate_settings`. */
sealed class ValidationResult {
    data class Error(val code: String) : ValidationResult()
    data class Ok(val config: ConfigStore.AppConfig) : ValidationResult()
}

fun validateSettings(values: ConfigStore.AppConfig): ValidationResult {
    val cfg = values.copy()

    if (!isValidIpv4(cfg.host.trim())) return ValidationResult.Error("bad_host")
    cfg.host = cfg.host.trim()

    if (cfg.port !in 1..65535) return ValidationResult.Error("bad_port")

    val dcLines = cfg.dcIp.map { it.trim() }.filter { it.isNotEmpty() }
    try {
        parseDcIpList(dcLines)
    } catch (e: Exception) {
        return ValidationResult.Error("dc_invalid")
    }
    cfg.dcIp = dcLines.toMutableList()

    val secret = cfg.secret.trim()
    if (secret.length != 32) return ValidationResult.Error("bad_secret_len")
    if (secret.any { Character.digit(it, 16) < 0 }) {
        return ValidationResult.Error("bad_secret_hex")
    }
    cfg.secret = secret

    return ValidationResult.Ok(cfg)
}
