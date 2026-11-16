package tvrc.core.json

/**
 * 手写最小 JSON 解析器（零依赖，与自研 MiniHttpServer 同一原则）。
 * 只做读：B站 API 响应都是对象/数组/字符串/数字/布尔/null，够用。
 */
sealed interface JsonValue {

    data class Obj(val fields: Map<String, JsonValue>) : JsonValue

    data class Arr(val items: List<JsonValue>) : JsonValue

    data class Str(val value: String) : JsonValue

    data class Num(val raw: String) : JsonValue {
        val long: Long get() = raw.toDouble().toLong()
        val double: Double get() = raw.toDouble()
    }

    data class Bool(val value: Boolean) : JsonValue

    object Null : JsonValue
}

/** JsonValue 上的取值扩展：链式 .field(..)?.strOrNull() 全部对 JsonValue? 可用。 */
fun JsonValue?.field(name: String): JsonValue? = (this as? JsonValue.Obj)?.fields?.get(name)

fun JsonValue?.strOrNull(): String? = (this as? JsonValue.Str)?.value

fun JsonValue?.longOrNull(): Long? = (this as? JsonValue.Num)?.long

fun JsonValue?.arrOrNull(): List<JsonValue>? = (this as? JsonValue.Arr)?.items

/** 序列化（配置保存用；只覆盖配置需要的类型，格式稳定可 diff）。 */
fun JsonValue.write(): String = when (this) {
    is JsonValue.Null -> "null"
    is JsonValue.Bool -> value.toString()
    is JsonValue.Num -> raw
    is JsonValue.Str -> "\"" + Json.escape(value) + "\""
    is JsonValue.Obj -> fields.entries.joinToString(",", "{", "}") {
        "\"" + Json.escape(it.key) + "\":" + it.value.write()
    }
    is JsonValue.Arr -> items.joinToString(",", "[", "]") { it.write() }
}

class JsonException(message: String) : RuntimeException(message)

object Json {

    fun escape(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun parse(text: String): JsonValue {
        val p = Parser(text)
        val value = p.parseValue()
        p.skipWs()
        if (!p.eof()) throw JsonException("trailing content at offset " + p.pos)
        return value
    }
}

private class Parser(val text: String) {

    var pos = 0

    fun eof(): Boolean = pos >= text.length

    fun skipWs() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    fun parseValue(): JsonValue {
        skipWs()
        if (eof()) throw JsonException("unexpected end of input")
        return when (text[pos]) {
            '{' -> parseObj()
            '[' -> parseArr()
            '"' -> JsonValue.Str(parseString())
            't' -> parseLit("true", JsonValue.Bool(true))
            'f' -> parseLit("false", JsonValue.Bool(false))
            'n' -> parseLit("null", JsonValue.Null)
            else -> parseNum()
        }
    }

    private fun parseLit(word: String, value: JsonValue): JsonValue {
        if (pos + word.length > text.length || text.substring(pos, pos + word.length) != word) {
            throw JsonException("bad literal at offset " + pos)
        }
        pos += word.length
        return value
    }

    private fun parseObj(): JsonValue.Obj {
        expect('{')
        val map = LinkedHashMap<String, JsonValue>()
        skipWs()
        if (peek() == '}') {
            pos++
            return JsonValue.Obj(map)
        }
        while (true) {
            skipWs()
            if (peek() != '"') throw JsonException("expected key at offset " + pos)
            val key = parseString()
            skipWs()
            expect(':')
            val value = parseValue()
            map[key] = value
            skipWs()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return JsonValue.Obj(map)
                }
                else -> throw JsonException("expected ',' or '}' at offset " + pos)
            }
        }
    }

    private fun parseArr(): JsonValue.Arr {
        expect('[')
        val list = ArrayList<JsonValue>()
        skipWs()
        if (peek() == ']') {
            pos++
            return JsonValue.Arr(list)
        }
        while (true) {
            list.add(parseValue())
            skipWs()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return JsonValue.Arr(list)
                }
                else -> throw JsonException("expected ',' or ']' at offset " + pos)
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (eof()) throw JsonException("unterminated string")
            val c = text[pos]
            pos++
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (eof()) throw JsonException("unterminated escape")
                    val e = text[pos]
                    pos++
                    when (e) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) throw JsonException("bad unicode escape")
                            val hex = text.substring(pos, pos + 4)
                            pos += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> throw JsonException("bad escape char " + e)
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun parseNum(): JsonValue.Num {
        val start = pos
        if (peek() == '-') pos++
        while (!eof()) {
            val c = text[pos]
            if (c.isDigit() || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') pos++
            else break
        }
        if (pos == start) throw JsonException("bad number at offset " + start)
        return JsonValue.Num(text.substring(start, pos))
    }

    private fun peek(): Char {
        if (eof()) throw JsonException("unexpected end of input")
        return text[pos]
    }

    private fun expect(c: Char) {
        if (peek() != c) throw JsonException("expected '" + c + "' at offset " + pos)
        pos++
    }
}
