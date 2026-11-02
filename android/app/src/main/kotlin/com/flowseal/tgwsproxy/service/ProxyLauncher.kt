package com.flowseal.tgwsproxy.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Единая точка запуска [ProxyService]: startForegroundService с fallback на
 * обычный startService. Нужно, чтобы из Tile/BOOT/Alarm сервис не падал на
 * Android 8+/12+ с «Not allowed to start service Intent … not allowed».
 */
object ProxyLauncher {

    fun send(context: Context, action: String) {
        val intent = Intent(context, ProxyService::class.java).setAction(action)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            try {
                context.startService(intent)
            } catch (e2: Exception) {
                // Android 12+ FGS-start-restriction:背景启动被禁 — планируем
                // рестарт через exact alarm (это легальное исключение).
                android.util.Log.w("ProxyLauncher", "Direct start failed, scheduling alarm", e2)
                try {
                    ProxyRestart.schedule(context, 1_000)
                } catch (e3: Exception) {
                    android.util.Log.e("ProxyLauncher", "Alarm fallback failed", e3)
                }
            }
        }
    }
}
