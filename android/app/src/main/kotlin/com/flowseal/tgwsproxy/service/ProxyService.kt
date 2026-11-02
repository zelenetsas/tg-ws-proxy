package com.flowseal.tgwsproxy.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.flowseal.tgwsproxy.MainActivity
import com.flowseal.tgwsproxy.R
import com.flowseal.tgwsproxy.config.ConfigStore
import com.flowseal.tgwsproxy.log.Log
import com.flowseal.tgwsproxy.log.LogFile
import com.flowseal.tgwsproxy.proxy.ProxyServer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Состояние сервиса для UI и Tile. */
sealed class ProxyState {
    data object Stopped : ProxyState()
    data object Starting : ProxyState()
    data class Running(val link: String) : ProxyState()
    data class Failed(val message: String) : ProxyState()
}

/**
 * Foreground Service, оборачивающий [ProxyServer]: persistent-уведомление,
 * действия Стоп/Перезапуск.
 */
class ProxyService : Service() {

    companion object {
        const val CHANNEL_ID = "proxy_status"
        const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.flowseal.tgwsproxy.START"
        const val ACTION_STOP = "com.flowseal.tgwsproxy.STOP"
        const val ACTION_RESTART = "com.flowseal.tgwsproxy.RESTART"

        private val _state = MutableStateFlow<ProxyState>(ProxyState.Stopped)
        val state: StateFlow<ProxyState> = _state

        @Volatile
        var instance: ProxyService? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ProxyServer? = null
    private var networkMonitor: NetworkMonitor? = null
    private lateinit var configStore: ConfigStore

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** true, когда остановку запросил пользователь (не планируем watchdog-рестарт). */
    @Volatile
    private var intentionalStop = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        configStore = ConfigStore(File(applicationInfo.dataDir, "config.json"))
        // Лог: экран (logcat) + файл с ротацией
        val logFile = LogFile(
            File(applicationInfo.dataDir, "files/proxy.log"),
            logMaxMb = configStore.load().logMaxMb,
        )
        Log.sink = { line ->
            android.util.Log.i("TgWsProxy", line)
            logFile.append(line)
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY redelivery: intent == null после убийства системой
        when (intent?.action) {
            ACTION_STOP -> {
                intentionalStop = true
                ProxyRestart.cancel(this)
                // Сервис мог быть запущен через startForegroundService: контракт
                // требует startForeground() — выполняем, затем останавливаем.
                if (_state.value is ProxyState.Stopped || _state.value is ProxyState.Failed) {
                    promoteToForeground()
                }
                stopProxy()
                return START_NOT_STICKY
            }

            ACTION_RESTART -> serviceScope.launch {
                server?.stop()
                server = null
                _state.value = ProxyState.Stopped
                startProxy()
            }

            else -> startProxy()
        }
        return START_STICKY
    }

    /**
     * Свайп карточки из «Недавних» (HyperOS убивает процесс вместе с задачей):
     * планируем рестарт через AlarmManager — PendingIntent переживает гибель процесса.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!intentionalStop && _state.value !is ProxyState.Stopped) {
            try {
                ProxyRestart.schedule(applicationContext, 2_000)
                Log.warn("Task removed: proxy restart scheduled (swipe-to-kill guard)")
            } catch (e: Exception) {
                Log.error("Task removed guard failed", e)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Watchdog: если убили не по желанию пользователя — планируем рестарт.
        if (!intentionalStop && (_state.value is ProxyState.Running || _state.value is ProxyState.Starting)) {
            try {
                ProxyRestart.schedule(applicationContext, 3_000)
                Log.warn("Service destroyed unexpectedly: restart scheduled")
            } catch (e: Exception) {
                Log.error("Watchdog restart failed", e)
            }
        }
        instance = null
        networkMonitor?.unregister()
        networkMonitor = null
        releaseLocks()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startProxy() {
        if (_state.value is ProxyState.Running || _state.value is ProxyState.Starting) {
            promoteToForeground()
            return
        }
        intentionalStop = false
        _state.value = ProxyState.Starting
        promoteToForeground()

        serviceScope.launch {
            val appCfg = configStore.load()
            Log.verbose = appCfg.verbose
            val srv = ProxyServer(configStore.toProxyConfig(appCfg))
            server = srv
            srv.start().fold(
                onSuccess = {
                    _state.value = ProxyState.Running(srv.buildLink())
                    acquireLocks()
                    registerNetworkMonitor()
                    ProxyRestart.cancel(this@ProxyService)
                },
                onFailure = { e ->
                    _state.value = ProxyState.Failed(e.message ?: e.javaClass.simpleName)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                },
            )
        }
    }

    private fun stopProxy() {
        serviceScope.launch {
            networkMonitor?.unregister()
            networkMonitor = null
            server?.stop()
            server = null
            _state.value = ProxyState.Stopped
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Частичный WakeLock держит CPU для шифрования/ротации пулов при погасшем
     * экране, WifiLock не даёт Wi-Fi радиомодулю переходить в экономичный режим.
     * Оба отпускаются при остановке, поэтому батарею «в стол» не жгут.
     */
    /**
     * Монитор смены сети: Wi-Fi <-> mobile сбрасывает WS-пулы, и без реакции
     * приложение «залипает» на мёртвых соединениях (главная мобильная боль
     * по данным анализа конкурентов).
     */
    private fun registerNetworkMonitor() {
        networkMonitor?.unregister()
        networkMonitor = NetworkMonitor(
            context = applicationContext,
            scope = serviceScope,
            onChanged = { server?.onNetworkChanged() },
        ).also { it.register() }
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TgWsProxy:proxy").apply {
                setReferenceCounted(false)
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "TgWsProxy:wifi",
            )
        }
        try {
            if (wakeLock?.isHeld != true) wakeLock?.acquire()
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
            Log.info("Wake/Wifi locks acquired")
        } catch (e: Exception) {
            Log.error("Failed to acquire locks", e)
        }
    }

    private fun releaseLocks() {
        try {
            while (wakeLock?.isHeld == true) wakeLock?.release()
            while (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        wifiLock = null
    }

    private fun promoteToForeground() {
        val linkText = when (val s = _state.value) {
            is ProxyState.Running -> s.link
            else -> getString(R.string.status_stopped)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, linkText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainPendingIntent())
            .addAction(stopAction())
            .addAction(restartAction())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun mainPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, ProxyService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun stopAction(): NotificationCompat.Action = NotificationCompat.Action(
        R.drawable.ic_notif,
        getString(R.string.action_stop),
        actionPendingIntent(ACTION_STOP, 1),
    )

    private fun restartAction(): NotificationCompat.Action = NotificationCompat.Action(
        R.drawable.ic_notif,
        getString(R.string.action_restart),
        actionPendingIntent(ACTION_RESTART, 2),
    )

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }
}
