package com.flowseal.tgwsproxy.proxy

/**
 * Крошечный JSON-парсер для тест-векторов (без внешних зависимостей).
 * Достаточен для vectors.json: объекты, массивы, строки, числа, литералы.
 */
internal object MiniJson {

    fun parse(text: String): Any = Parser(text).parseValue()

    private class Parser(private val s: String) {
        private var i = 0

        fun parseValue(): Any {
            skipWs()
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> {
                    expect("true"); true
                }
                'f' -> {
                    expect("false"); false
                }
                'n' -> {
                    expect("null"); Unit
                }
                else -> parseNumber()
            }
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun expect(word: String) {
            require(s.startsWith(word, i)) { "bad literal at $i" }
            i += word.length
        }

        private fun parseObject(): Map<String, Any> {
            val out = HashMap<String, Any>()
            i++
            skipWs()
            if (s[i] == '}') {
                i++
                return out
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                require(s[i] == ':') { "expected : at $i" }
                i++
                out[key] = parseValue()
                skipWs()
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return out
                    }
                    else -> throw IllegalStateException("expected , or } at $i")
                }
            }
        }

        private fun parseArray(): List<Any> {
            val out = ArrayList<Any>()
            i++
            skipWs()
            if (s[i] == ']') {
                i++
                return out
            }
            while (true) {
                out.add(parseValue())
                skipWs()
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return out
                    }
                    else -> throw IllegalStateException("expected , or ] at $i")
                }
            }
        }

        private fun parseString(): String {
            require(s[i] == '"')
            i++
            val sb = StringBuilder()
            while (s[i] != '"') {
                val c = s[i]
                if (c == '\\') {
                    i++
                    when (s[i]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'u' -> {
                            sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(s[i])
                    }
                    i++
                } else {
                    sb.append(c)
                    i++
                }
            }
            i++
            return sb.toString()
        }

        private fun parseNumber(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }
    }

    /** Список чисел 0..255 -> ByteArray. */
    fun toBytes(value: Any?): ByteArray = when (value) {
        is List<*> -> ByteArray(value.size) {
            val v = (value[it] as Number).toInt()
            require(v in 0..255) { "byte out of range: $v" }
            v.toByte()
        }
        else -> error("cannot convert $value")
    }
}
