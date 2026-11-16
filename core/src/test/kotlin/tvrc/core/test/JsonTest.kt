package tvrc.core.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tvrc.core.json.Json
import tvrc.core.json.JsonException
import tvrc.core.json.JsonValue
import tvrc.core.json.arrOrNull
import tvrc.core.json.field
import tvrc.core.json.longOrNull
import tvrc.core.json.strOrNull

class JsonTest {

    @Test
    fun flatObject() {
        val v = Json.parse("""{"code":0,"message":"ok"}""") as JsonValue.Obj
        assertEquals(0L, v.field("code")?.longOrNull())
        assertEquals("ok", v.field("message")?.strOrNull())
    }

    @Test
    fun nestedArraysAndObjects() {
        val v = Json.parse("""{"data":{"list":[{"id":1},{"id":2}],"tags":["a","b"]}}""") as JsonValue.Obj
        val list = v.field("data")?.field("list")?.arrOrNull()
        assertEquals(2, list?.size)
        assertEquals(2L, list?.get(1)?.field("id")?.longOrNull())
        assertEquals("b", v.field("data")?.field("tags")?.arrOrNull()?.get(1)?.strOrNull())
    }

    @Test
    fun stringEscapes() {
        val v = Json.parse("""{"s":"a\nb\"c\u4e2dd"}""") as JsonValue.Obj
        assertEquals("a\nb\"c中d", v.field("s")?.strOrNull())
    }

    @Test
    fun numbersBoolNull() {
        val v = Json.parse("""{"i":42,"f":3.5,"neg":-7,"b":true,"n":null}""") as JsonValue.Obj
        assertEquals(42L, v.field("i")?.longOrNull())
        assertEquals(3.5, (v.field("f") as JsonValue.Num).double, 1e-9)
        assertEquals(-7L, v.field("neg")?.longOrNull())
        assertEquals(true, (v.field("b") as JsonValue.Bool).value)
        assertEquals(JsonValue.Null, v.field("n"))
        assertNull(v.field("missing"))
    }

    @Test
    fun whitespaceTolerated() {
        val v = Json.parse("  { \"a\" : [ 1 , 2 ] } ") as JsonValue.Obj
        assertEquals(2, v.field("a")?.arrOrNull()?.size)
    }

    @Test
    fun malformedThrows() {
        assertThrows(JsonException::class.java) { Json.parse("{") }
        assertThrows(JsonException::class.java) { Json.parse("""{"a":1}x""") }
        assertThrows(JsonException::class.java) { Json.parse("[1,") }
        assertThrows(JsonException::class.java) { Json.parse("") }
    }
}
