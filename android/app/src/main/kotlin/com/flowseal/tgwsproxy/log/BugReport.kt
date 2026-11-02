package com.flowseal.tgwsproxy.log

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.flowseal.tgwsproxy.BuildConfig
import com.flowseal.tgwsproxy.proxy.Stats
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * «Собрать отчёт» (паттерн amurcanov): zip из лога, конфига (secret маскируется!)
 * и системной информации — порог репортинга падает в разы.
 */
object BugReport {

    fun createAndShare(context: Context) {
        val dir = File(context.applicationInfo.dataDir, "files/reports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zip = File(dir, "report-$stamp.zip")

        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            // 1. Лог прокси (уже цензурированный DomainCensor'ом)
            val logFile = File(context.applicationInfo.dataDir, "files/proxy.log")
            if (logFile.exists()) {
                zos.writeEntry("proxy.log", logFile)
                val log1 = File(logFile.parentFile, "${logFile.name}.1")
                if (log1.exists()) zos.writeEntry("proxy.log.1", log1)
            }

            // 2. Конфиг с замаскированным secret
            val cfgFile = File(context.applicationInfo.dataDir, "config.json")
            if (cfgFile.exists()) {
                val masked = cfgFile.readText().replace(
                    Regex("\"secret\"\\s*:\\s*\"[0-9a-fA-F]{32}\""),
                    "\"secret\": \"***masked***\"",
                )
                zos.putNextEntry(ZipEntry("config.json"))
                zos.write(masked.toByteArray())
                zos.closeEntry()
            }

            // 3. Сводка системы/состояния
            val info = buildString {
                appendLine("app: TgWsProxy ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("language: ${Locale.getDefault()}")
                appendLine("battery: ${batteryPercent(context)}%")
                appendLine("network: ${activeNetworkType(context)}")
                appendLine("proxy_running: ${com.flowseal.tgwsproxy.service.ProxyService.state.value::class.simpleName}")
                appendLine("stats: ${Stats.snapshot().summary()}")
                appendLine("time: ${Date()}")
            }
            zos.putNextEntry(ZipEntry("info.txt"))
            zos.write(info.toByteArray())
            zos.closeEntry()
        }

        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", zip,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_SUBJECT,
                "TgWsProxy report $stamp (${BuildConfig.VERSION_NAME})",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "TgWsProxy report").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun ZipOutputStream.writeEntry(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        FileInputStream(file).use { it.copyTo(this) }
        closeEntry()
    }

    private fun batteryPercent(context: Context): Int = try {
        val ifl = context.registerReceiver(
            null,
            android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val level = ifl?.getIntExtra("level", 0) ?: 0
        val scale = ifl?.getIntExtra("scale", 100) ?: 100
        if (scale == 0) -1 else level * 100 / scale
    } catch (e: Exception) {
        -1
    }

    private fun activeNetworkType(context: Context): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        @Suppress("DEPRECATION")
        cm.activeNetworkInfo?.type?.let { t ->
            when (t) {
                android.net.ConnectivityManager.TYPE_WIFI -> "wifi"
                android.net.ConnectivityManager.TYPE_MOBILE -> "mobile"
                else -> "type=$t"
            }
        } ?: "none"
    } catch (e: Exception) {
        "unknown"
    }
}
