package com.flowseal.tgwsproxy.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Порт `proxy/utils.py::DomainCensorFilter` + `utils/logging_setup.py`.
 *
 * Логи прокси содержат имена CF-доменов — они маскируются, чтобы лог нельзя
 * было использовать для их восстановления. telegram.org и *.log не трогаем.
 */
object DomainCensor {

    private val DOMAIN_RE = Regex(
        "(?<![\\w-])(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}(?![\\w-])",
    )

    fun censor(text: String): String =
        DOMAIN_RE.replace(text) { match -> censorDomain(match.value) }

    private fun censorDomain(domain: String): String {
        val normalized = domain.lowercase(Locale.ROOT).removeSuffix(".")
        if (normalized == "telegram.org" || normalized.endsWith(".telegram.org") ||
            normalized.endsWith(".log")
        ) {
            return domain
        }
        val parts = domain.split('.')
        if (parts.size < 2) return domain
        return parts.mapIndexed { i, part ->
            if (i == parts.size - 1) part
            else part.substring(0, part.length / 2) + "*".repeat(part.length - part.length / 2)
        }.joinToString(".")
    }
}

enum class LogLevel(val tag: String) { DEBUG("D"), INFO("I"), WARN("W"), ERROR("E") }

/**
 * Минимальный логгер уровня Python `logging`: консоль (logcat) + файл с
 * ротацией. Формат строк совпадает с десктопной версией, чтобы один и тот же
 * парсер подходил к логам обеих платформ.
 */
class Logger(
    private val minLevel: LogLevel = LogLevel.INFO,
    private val sink: (String) -> Unit,
) {
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun isDebugEnabled(): Boolean = minLevel == LogLevel.DEBUG

    fun debug(message: String) = log(LogLevel.DEBUG, message)
    fun info(message: String) = log(LogLevel.INFO, message)
    fun warn(message: String) = log(LogLevel.WARN, message)
    fun error(message: String, t: Throwable? = null) =
        log(LogLevel.ERROR, if (t == null) message else "$message :: ${t.javaClass.simpleName}: ${t.message}")

    private fun log(level: LogLevel, message: String) {
        if (level.ordinal < minLevel.ordinal) return
        val line = "${timeFormat.format(Date())}  ${level.tag.padEnd(5)}  " +
            DomainCensor.censor(message)
        sink(line)
    }
}
