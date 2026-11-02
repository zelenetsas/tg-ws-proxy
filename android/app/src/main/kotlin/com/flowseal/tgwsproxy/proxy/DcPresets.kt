package com.flowseal.tgwsproxy.proxy

/**
 * Пресеты списка DC:IP для настроек (фича из анализа конкурентов:
 * топ-жалоба оригинала — «не грузится медиа» из DC, кроме 2/4).
 *
 * Пустой список ([ALL_VIA_CF]) — легальный режим: WS-пул отключается,
 * всё идёт через цепочку фолбэков (CF Worker -> CF proxy), которая
 * работает для ЛЮБЫХ DC — ровно рекомендация из FAQ десктопной версии.
 */
object DcPresets {
    const val DEFAULT = "default"
    const val ALL_VIA_CF = "all_via_cf"
    const val CUSTOM = "custom"

    val DEFAULT_LINES: List<String> = listOf(
        "2:149.154.167.220",
        "4:149.154.167.220",
    )

    val ALL_VIA_CF_LINES: List<String> = emptyList()

    fun presetFor(lines: List<String>): String {
        val clean = lines.map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            clean.isEmpty() -> ALL_VIA_CF
            clean.sorted() == DEFAULT_LINES.sorted() -> DEFAULT
            else -> CUSTOM
        }
    }
}
