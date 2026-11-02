package com.flowseal.tgwsproxy.proxy

import com.flowseal.tgwsproxy.log.Log

/**
 * Порт `proxy/config.py` в части CF-доменов.
 *
 * В десктопной версии список доменов хранится «зашифрованным» (каждая буква
 * сдвинута на число букв в строке) — это не защита, а способ не отдавать
 * готовый список ботам, которые парсят репозиторий. Формула сохранена
 * байт-в-байт, чтобы пул совпадал с десктопным.
 */
object CfDomains {

    private const val CFPROXY_DOMAINS_URL =
        "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"

    private const val MIN_VALID_DOMAINS = 3

    private const val SUFFIX = ".co.uk"

    private val ENCODED: List<String> = listOf(
        "virkgj.com",
        "vmmzovy.com",
        "mkuosckvso.com",
        "zaewayzmplad.com",
        "twdmbzcm.com",
        "awzwsldi.com",
        "clngqrflngqin.com",
        "tjacxbqtj.com",
        "bxaxtxmrw.com",
        "dmohrsgmohcrwb.com",
        "vwbmtmoi.com",
        "khgrre.com",
        "ulihssf.com",
        "tmhqsdqmfpmk.com",
        "xwuwoqbm.com",
        "orgcnunpj.com",
        "zhkuldz.com",
        "zypoljnslxa.com",
        "efabnxaowuzs.com",
        "zaftuzsftqdq.com",
    )

    /** Порт `_dd`: обратное смещение букв на количество букв в основе. */
    fun decode(encoded: String): String {
        if (!encoded.endsWith(".com")) return encoded
        val body = encoded.substring(0, encoded.length - 4)
        val shift = body.count { it.isLetter() }
        val out = StringBuilder(body.length + SUFFIX.length)
        for (ch in body) {
            when {
                ch in 'a'..'z' -> out.append((((ch - 'a') - shift).mod(26) + 'a'.code).toChar())
                ch in 'A'..'Z' -> out.append((((ch - 'A') - shift).mod(26) + 'A'.code).toChar())
                else -> out.append(ch)
            }
        }
        out.append(SUFFIX)
        return out.toString()
    }

    /** Зашитые домены — используются, пока GitHub-список не загружен. */
    val DEFAULT: List<String> = ENCODED.map(::decode)

    /** Порт `_is_valid_domain`. */
    fun isValidDomain(domain: String): Boolean {
        if (domain.isEmpty() || domain.length > 253) return false
        if (domain.startsWith('.') || domain.endsWith('.')) return false
        val labels = domain.split('.')
        if (labels.size < 2) return false
        for (label in labels) {
            if (label.isEmpty() || label.length > 63) return false
            if (label.startsWith('-') || label.endsWith('-')) return false
            if (!label.all { it.isLetterOrDigit() || it == '-' }) return false
        }
        val tld = labels.last()
        if (tld.length < 2 || tld.none { it.isLetter() }) return false
        return true
    }

    /** Порт `_normalize_domain_pool`. */
    fun normalizePool(domains: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (raw in domains) {
            val item = raw.trim().lowercase()
            if (!isValidDomain(item)) continue
            if (!seen.add(item)) continue
            out.add(item)
        }
        return out
    }

    /**
     * Порт `_fetch_cfproxy_domain_list`: тянет зашифрованный список с GitHub.
     * Сетевой вызов делает вызывающая сторона через [fetchText].
     */
    fun parseFetched(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map(::decode)
            .toList()

    /**
     * Обновление пула (порт `refresh_cfproxy_domains`).
     * Возвращает true, если пул реально обновился.
     */
    suspend fun refresh(
        balancer: Balancer,
        userDomains: List<String>,
        fetchText: suspend (String) -> String?,
    ): Boolean {
        if (userDomains.isNotEmpty()) return false

        val url = CFPROXY_DOMAINS_URL + "?" + urandom(4).toHexLower()
        val body = try {
            fetchText(url)
        } catch (e: Exception) {
            Log.warn("Failed to fetch CF proxy domain list: ${e.javaClass.simpleName}")
            null
        }

        if (body == null) {
            Log.warn("CF proxy domain refresh failed or empty response; keeping current domain pool")
            return false
        }

        val fetched = parseFetched(body)
        val pool = normalizePool(fetched)
        if (pool.size >= MIN_VALID_DOMAINS) {
            balancer.updateDomainsList(pool)
            Log.info("CF proxy domain pool updated from GitHub (${pool.size} domains)")
            return true
        }

        if (fetched.isNotEmpty()) {
            Log.warn(
                "Ignoring fetched CF proxy domains due to low-quality payload " +
                    "(total=${fetched.size}, valid=${pool.size}, required>=$MIN_VALID_DOMAINS); " +
                    "keeping current domain pool",
            )
        } else {
            Log.warn("CF proxy domain refresh returned empty payload; keeping current domain pool")
        }
        return false
    }
}
