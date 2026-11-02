package com.flowseal.tgwsproxy.proxy

import kotlin.random.Random

/**
 * Порт `proxy/balancer.py`.
 *
 * Хранит пул CF-доменов и закреплённый за каждым DC активный домен,
 * чтобы переключения были редкими, а список всегда обходился по кругу.
 */
class Balancer {
    @Volatile var domains: List<String> = emptyList()
        private set

    private val lock = Any()
    private var dcToDomain: HashMap<Int, String> = HashMap()

    /** Порт `update_domains_list`: не пересобирать при идентичном списке. */
    fun updateDomainsList(domainsList: List<String>) {
        synchronized(lock) {
            if (sameMultiset(domains, domainsList)) return
            domains = domainsList.toList()
            if (domains.isEmpty()) {
                dcToDomain = HashMap()
                return
            }
            val next = HashMap<Int, String>()
            for (dc in intArrayOf(1, 2, 3, 4, 5, 203)) {
                next[dc] = domains.random(Random.Default)
            }
            dcToDomain = next
        }
    }

    private fun sameMultiset(a: List<String>, b: List<String>): Boolean {
        if (a.size != b.size) return false
        val ca = HashMap<String, Int>(a.size * 2)
        for (x in a) ca[x] = (ca[x] ?: 0) + 1
        val cb = HashMap<String, Int>(b.size * 2)
        for (x in b) cb[x] = (cb[x] ?: 0) + 1
        return ca == cb
    }

    /** Порт `update_domain_for_dc`: true, если домен действительно сменился. */
    fun updateDomainForDc(dcId: Int, domain: String): Boolean = synchronized(lock) {
        if (dcToDomain[dcId] == domain) return false
        dcToDomain[dcId] = domain
        true
    }

    /** Порт `get_domains_for_dc`: сначала текущий, затем остальные вперемешку. */
    fun domainsForDc(dcId: Int): List<String> = synchronized(lock) {
        val current = dcToDomain[dcId]
        val out = ArrayList<String>(domains.size + 1)
        if (current != null) out.add(current)
        val rest = domains.toMutableList()
        rest.shuffle(Random.Default)
        for (d in rest) if (d != current) out.add(d)
        out
    }

    fun snapshot(): Pair<List<String>, Map<Int, String>> = synchronized(lock) {
        domains.toList() to dcToDomain.toMap()
    }

    fun clear() = synchronized(lock) {
        domains = emptyList()
        dcToDomain = HashMap()
    }
}
