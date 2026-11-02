package com.flowseal.tgwsproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flowseal.tgwsproxy.config.ConfigStore
import com.flowseal.tgwsproxy.log.Log
import java.io.File

/**
 * Старт прокси после перезагрузки.
 * На HyperOS сработает только если пользователь включил «Автозапуск» приложения
 * (см. экран «Фон»); без этого система не доставит broadcast.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val cfg = try {
            ConfigStore(File(context.applicationInfo.dataDir, "config.json")).load()
        } catch (e: Exception) {
            null
        } ?: return

        if (!cfg.autostart) return
        try {
            ProxyRestart.schedule(context, 3_000)
            Log.info("Boot completed: proxy restart scheduled")
        } catch (e: Exception) {
            Log.error("Boot autostart failed", e)
        }
    }
}

/**
 * Планирует рестарт сервиса через AlarmManager: exact-and-allow-while-idle
 * будит приложение даже в Doze, а PendingIntent на сервис переживает
 * убийство процесса (в отличие от внутренних корутин).
 */
object ProxyRestart {

    private const val REQ_CODE = 42

    fun schedule(context: Context, delayMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        // getForegroundService: сервис обязан вызвать startForeground() — иначе
        // Android 12+ не даст запустить FGS из background-PendingIntent.
        val pi = android.app.PendingIntent.getForegroundService(
            context,
            REQ_CODE,
            Intent(context, ProxyService::class.java).setAction(ProxyService.ACTION_START),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val at = System.currentTimeMillis() + delayMs
        try {
            if (android.os.Build.VERSION.SDK_INT >= 23 && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        // тот же тип PendingIntent, что и в schedule() — иначе cancel не найдёт его
        val pi = android.app.PendingIntent.getForegroundService(
            context,
            REQ_CODE,
            Intent(context, ProxyService::class.java).setAction(ProxyService.ACTION_START),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        am.cancel(pi)
    }
}
