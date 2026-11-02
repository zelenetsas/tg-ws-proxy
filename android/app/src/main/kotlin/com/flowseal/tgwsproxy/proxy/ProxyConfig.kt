package com.flowseal.tgwsproxy.proxy

/**
 * Порт `proxy/config.py::ProxyConfig`.
 * Экземпляр создаётся один раз в сервисе и передаётся во все компоненты
 * (в Python это глобальный singleton `proxy_config`).
 */
class ProxyConfig(
    var port: Int = 1443,
    var host: String = "127.0.0.1",
    var secret: String = urandom(16).toHexLower(),
    var dcRedirects: Map<Int, String> = DEFAULT_DC_REDIRECTS.toMap(),
    var bufferSize: Int = 256 * 1024,
    var poolSize: Int = 4,
    var fallbackCfproxy: Boolean = true,
    var cfproxyUserDomains: List<String> = emptyList(),
    var cfproxyWorkerDomains: List<String> = emptyList(),
    var cfproxyH2Media: Boolean = true,
    var disableSecure: Boolean = false,
    var fakeTlsDomain: String = "",
    var proxyProtocol: Boolean = false,
    var forceTestDc: Boolean = false,
) {
    /** Порт свойства `h2_enabled`. HTTP/2 пул включается позже (этап 8). */
    val h2Enabled: Boolean
        get() = cfproxyH2Media && fallbackCfproxy && !disableSecure && !forceTestDc

    val secretBytes: ByteArray
        get() = hexToBytes(secret) ?: error("Secret must be 32 hex chars")

    companion object {
        val DEFAULT_DC_REDIRECTS: Map<Int, String> = mapOf(
            2 to "149.154.167.220",
            4 to "149.154.167.220",
        )
    }
}
