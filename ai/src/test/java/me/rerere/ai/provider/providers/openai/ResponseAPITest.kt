package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * summarizeTools 诊断函数测试：400 定位（如 "tools[i].function: missing field name"）
 * 的关键路径——必须准确展示每个工具的类型/name（扁平+嵌套两种格式）。
 */
class ResponseAPITest {

    @Test
    fun `null 与空输入`() {
        assertEquals("null", summarizeTools(null))
        assertEquals("[]", summarizeTools(buildJsonArray { }))
    }

    @Test
    fun `非数组输入原样截断展示`() {
        val obj = buildJsonObject { put("type", "web_search") }
        assertTrue(summarizeTools(obj).contains("web_search"))
    }

    @Test
    fun `扁平格式工具展示顶层 name`() {
        val tools = buildJsonArray {
            add(buildJsonObject {
                put("type", "function")
                put("name", "search")
                put("description", "Search the web")
            })
        }
        val result = summarizeTools(tools)
        assertTrue(result.contains("[0] type=function name=search"))
        assertTrue(result.contains("desc=15ch"))
    }

    @Test
    fun `嵌套格式工具展示 function name`() {
        val tools = buildJsonArray {
            add(buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "nested_tool")
                }
            })
        }
        val result = summarizeTools(tools)
        assertTrue(result.contains("name=nested_tool"))
    }

    @Test
    fun `缺 name 标记 MISSING`() {
        val tools = buildJsonArray {
            add(buildJsonObject {
                put("type", "function")
                put("description", "no name here")
            })
        }
        val result = summarizeTools(tools)
        assertTrue("缺 name 必须标 MISSING", result.contains("name=MISSING"))
    }

    @Test
    fun `混合格式逐项展示`() {
        val tools = buildJsonArray {
            add(buildJsonObject { put("type", "web_search") })
            add(buildJsonObject {
                put("type", "function")
                put("name", "ok_tool")
            })
            add(buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "nested")
                }
            })
            add(buildJsonObject {
                put("type", "function")
                put("description", "broken")
            })
        }
        val result = summarizeTools(tools)
        val lines = result.lines()
        assertEquals(4, lines.size)
        assertTrue(lines[0].contains("[0] type=web_search name=MISSING"))
        assertTrue(lines[1].contains("[1] type=function name=ok_tool"))
        assertTrue(lines[2].contains("[2] type=function name=nested"))
        assertTrue(lines[3].contains("[3] type=function name=MISSING"))
    }
}
