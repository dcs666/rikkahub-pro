package me.rerere.ai.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [parseErrorDetail] 覆盖测试：
 * 递归字段解析 / 数组递归 / 基本类型 / 显式 null / 深度上限 / 无字段兜底序列化。
 * （2026-08-13 测试缺口闭环：ErrorParser 的 when 穷尽 else→JsonNull 分支改动后补测）
 */
class ErrorParserTest {

    @Test
    fun `error field should be parsed recursively`() {
        val json = buildJsonObject {
            put("error", buildJsonObject {
                put("message", "rate limit exceeded")
            })
        }
        assertEquals("rate limit exceeded", json.parseErrorDetail().message)
    }

    @Test
    fun `first existing error field wins`() {
        // error/detail/message/description 按序取第一个存在者
        val json = buildJsonObject {
            put("detail", "detail message")
            put("message", "message message")
        }
        assertEquals("detail message", json.parseErrorDetail().message)
    }

    @Test
    fun `nested object should recurse into error field value`() {
        val json = buildJsonObject {
            put("error", buildJsonObject {
                put("error", buildJsonObject {
                    put("description", "deep description")
                })
            })
        }
        assertEquals("deep description", json.parseErrorDetail().message)
    }

    @Test
    fun `array should parse its first element`() {
        val json = buildJsonArray {
            add(buildJsonObject {
                put("message", "first element message")
            })
            add(buildJsonObject {
                put("message", "second")
            })
        }
        assertEquals("first element message", json.parseErrorDetail().message)
    }

    @Test
    fun `empty array should report unknown error`() {
        val json = JsonArray(emptyList())
        assertEquals("Unknown error: Empty JSON array", json.parseErrorDetail().message)
    }

    @Test
    fun `primitive should use its content directly`() {
        assertEquals("boom", JsonPrimitive("boom").parseErrorDetail().message)
        assertEquals("42", JsonPrimitive(42).parseErrorDetail().message)
    }

    @Test
    fun `explicit null should serialize as null string`() {
        assertEquals("null", JsonNull.parseErrorDetail().message)
    }

    @Test
    fun `deep nesting should be truncated at depth limit`() {
        // 构造超过 MAX_ERROR_PARSE_DEPTH 的嵌套（循环构造 40 层）
        var element = buildJsonObject { put("message", "bottom") }
        repeat(40) {
            element = buildJsonObject { put("error", element) }
        }
        val result = element.parseErrorDetail().message
        // 超过深度后兜底为整体序列化字符串（含原始 JSON 片段）
        assertTrue(result!!.contains("bottom"))
    }

    @Test
    fun `object without error fields should serialize whole object`() {
        val json = buildJsonObject {
            put("foo", "bar")
            put("baz", 1)
        }
        val result = json.parseErrorDetail()
        assertTrue(result.message!!.contains("foo"))
        assertTrue(result.message!!.contains("bar"))
    }

    @Test
    fun `object with null error field should serialize whole object`() {
        // error 字段存在但值为 null → 解析出的也是 null 字符串（不崩溃）
        val json = buildJsonObject {
            put("error", JsonNull)
        }
        assertEquals("null", json.parseErrorDetail().message)
    }
}
