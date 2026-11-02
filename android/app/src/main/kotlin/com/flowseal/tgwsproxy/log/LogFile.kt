package com.flowseal.tgwsproxy.log

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Порт `utils/logging_setup.py`: файл лога с ротацией.
 *
 * Python-версия гарантирует хотя бы один бэкап (иначе `maxBytes` игнорируется),
 * и держит нижнюю границу размера 32 КБ — здесь те же инварианты.
 */
class LogFile(
    private val file: File,
    logMaxMb: Double = 5.0,
    private val backups: Int = 1,
) {
    private val lock = Any()
    private val maxBytes: Long = maxOf(MIN_BYTES, (logMaxMb * 1024 * 1024).toLong())
    private val backupCount: Int = maxOf(1, backups)

    init {
        file.parentFile?.mkdirs()
    }

    fun append(line: String) {
        synchronized(lock) {
            try {
                rotateIfNeeded()
                FileOutputStream(file, true).use { out ->
                    out.write((line + "\n").toByteArray(Charsets.UTF_8))
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun rotateIfNeeded() {
        if (!file.exists() || file.length() < maxBytes) return
        for (i in backupCount downTo 1) {
            val src = if (i == 1) file else File(file.parentFile, "${file.name}.${i - 1}")
            val dst = File(file.parentFile, "${file.name}.$i")
            if (!src.exists()) continue
            if (dst.exists()) dst.delete()
            src.renameTo(dst)
        }
        file.delete()
    }

    /** Последние [maxLines] строк (для экрана логов). */
    fun tail(maxLines: Int = 500, maxChars: Int = 200_000): String {
        synchronized(lock) {
            if (!file.exists()) return ""
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val length = raf.length()
                    val start = maxOf(0L, length - maxChars)
                    raf.seek(start)
                    val buf = ByteArray((length - start).toInt())
                    raf.readFully(buf)
                    val text = String(buf, Charsets.UTF_8)
                    val lines = text.split('\n')
                    lines.takeLast(maxLines).joinToString("\n")
                }
            } catch (_: Exception) {
                ""
            }
        }
    }

    fun readAll(): String = synchronized(lock) {
        try {
            if (file.exists()) file.readText() else ""
        } catch (_: Exception) {
            ""
        }
    }

    fun clear() {
        synchronized(lock) {
            try {
                file.delete()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val MIN_BYTES = 32L * 1024L
    }
}

/** Глобальный логгер приложения (аналог `logging.getLogger('tg-mtproto-proxy')`). */
object Log {
    @Volatile
    var sink: ((String) -> Unit)? = null

    @Volatile
    var verbose: Boolean = false

    val logger: Logger
        get() = Logger(
            minLevel = if (verbose) LogLevel.DEBUG else LogLevel.INFO,
            sink = { line -> sink?.invoke(line) },
        )

    fun debug(message: String) = logger.debug(message)
    fun info(message: String) = logger.info(message)
    fun warn(message: String) = logger.warn(message)
    fun error(message: String, t: Throwable? = null) = logger.error(message, t)
}
