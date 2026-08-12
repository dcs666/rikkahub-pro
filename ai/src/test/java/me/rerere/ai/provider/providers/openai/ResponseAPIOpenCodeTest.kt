package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for OpenCode Zen gateway (opencode.ai) Responses API handling:
 * - reasoning.effort: App's XHIGH is mapped to "max", MEDIUM to "high"
 *   (与 DeepSeek 官方一致，zen 网关代理 DeepSeek 系模型，官方枚举 low/high/max)
 * - reasoning history items use content array of reasoning_text (网关 Console provider
 *   要求：The reasoning_text in the thinking mode must be passed back to the API)
 * - 不请求 reasoning.summary / reasoning.encrypted_content（网关不支持）
 */
class ResponseAPIOpenCodeTest {

    private lateinit var api: ResponseAPI

    @Before
    fun setUp() {
        api = ResponseAPI(OkHttpClient())
    }

    private fun invokeBuildRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        params: TextGenerationParams,
    ): JsonObject {
        return buildRequestBody(providerSetting, listOf(UIMessage.user("hi")), params, stream = false)
    }

    private fun reasoningParams(reasoningLevel: ReasoningLevel): TextGenerationParams {
        return TextGenerationParams(
            model = Model(
                modelId = "deepseek-v4-pro",
                abilities = listOf(ModelAbility.REASONING)
            ),
            reasoningLevel = reasoningLevel,
        )
    }

    private fun openCodeSetting() = ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/zen/v1")

    @Test
    fun `xhigh maps to effort max on opencode zen responses api`() {
        val body = invokeBuildRequestBody(openCodeSetting(), reasoningParams(ReasoningLevel.XHIGH))
        assertEquals("max", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `medium maps to effort high on opencode zen responses api`() {
        val body = invokeBuildRequestBody(openCodeSetting(), reasoningParams(ReasoningLevel.MEDIUM))
        assertEquals("high", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `low keeps effort low on opencode zen responses api`() {
        val body = invokeBuildRequestBody(openCodeSetting(), reasoningParams(ReasoningLevel.LOW))
        assertEquals("low", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `auto omits effort on opencode zen responses api`() {
        val body = invokeBuildRequestBody(openCodeSetting(), reasoningParams(ReasoningLevel.AUTO))
        assertNull(body["reasoning"]?.jsonObject?.get("effort"))
    }

    @Test
    fun `off sends effort none on opencode zen responses api`() {
        val body = invokeBuildRequestBody(openCodeSetting(), reasoningParams(ReasoningLevel.OFF))
        assertEquals("none", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `opencode does not request summary or encrypted content`() {
        val body = invokeBuildRequestBody(openCodeSetting(), reasoningParams(ReasoningLevel.HIGH))
        assertNull(body["reasoning"]?.jsonObject?.get("summary"))
        assertNull(body["include"])
    }

    @Test
    fun `opencode reasoning history uses reasoning_text array content`() {
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "thinking trace"),
                UIMessagePart.Text("answer")
            )
        )
        val items = buildMessages(listOf(assistant), useReasoningTextArray = true)
        val reasoningItem = items.jsonArray.first { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }
        assertNull(reasoningItem.jsonObject["summary"])
        val content = reasoningItem.jsonObject["content"]?.jsonArray
        assertTrue(content != null && content.isNotEmpty())
        assertEquals("reasoning_text", content!![0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("thinking trace", content[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `old reasoning beyond last 4 turns is placeholder`() {
        // [L1] 思维链压缩（overflow 时）：最近 4 轮 assistant 保留完整思维链，更早的用占位符
        val msgs = mutableListOf<UIMessage>()
        // 5 轮 assistant（每轮带思维链），夹在 user 消息之间
        for (i in 0 until 5) {
            msgs.add(UIMessage.user("question $i"))
            msgs.add(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Reasoning(reasoning = "thinking trace $i"),
                        UIMessagePart.Text("answer $i")
                    )
                )
            )
        }
        msgs.add(UIMessage.user("final"))
        // 触发 overflow：超大 user 消息（450K 字符 ≈ 112.5K tokens ≥ 108K）
        msgs.add(UIMessage.user("x".repeat(500_000)))
        val items = buildMessages(msgs, useReasoningTextArray = true)
        val reasoningItems = items.jsonArray.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }
        assertEquals(5, reasoningItems.size)
        // 最早 1 轮（thinking trace 0）→ 占位符
        val first = reasoningItems[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("…", first["text"]?.jsonPrimitive?.content)
        // 最近 4 轮 → 完整保留
        for (i in 1 until 5) {
            val text = reasoningItems[i].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content
            assertEquals("thinking trace $i", text)
        }
    }

    @Test
    fun `no overflow still compresses old reasoning beyond last 4`() {
        // [L1] 思维链增量（2026-08-10 实测：服务端不读历史思维链）：
        // 无条件保留最近 4 轮完整，更早占位——与 overflow 无关
        val msgs = mutableListOf<UIMessage>()
        for (i in 0 until 5) {
            msgs.add(UIMessage.user("question $i"))
            msgs.add(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Reasoning(reasoning = "thinking trace $i"),
                        UIMessagePart.Text("answer $i")
                    )
                )
            )
        }
        msgs.add(UIMessage.user("final"))
        val items = buildMessages(msgs, useReasoningTextArray = true)
        val reasoningItems = items.jsonArray.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }
        assertEquals(5, reasoningItems.size)
        // 第 1 轮（最旧）：占位
        val first = reasoningItems[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content
        assertEquals("…", first)
        // 最近 4 轮：完整
        for (i in 1 until 5) {
            val text = reasoningItems[i].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content
            assertEquals("thinking trace $i", text)
        }
    }

    @Test
    fun `recent 4 turns reasoning kept complete when exactly 4`() {
        // [L1] 边界：恰好 4 轮时全部保留完整
        val msgs = mutableListOf<UIMessage>()
        for (i in 0 until 4) {
            msgs.add(UIMessage.user("question $i"))
            msgs.add(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Reasoning(reasoning = "thinking trace $i"),
                        UIMessagePart.Text("answer $i")
                    )
                )
            )
        }
        val items = buildMessages(msgs, useReasoningTextArray = true)
        val reasoningItems = items.jsonArray.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }
        assertEquals(4, reasoningItems.size)
        for (i in 0 until 4) {
            val text = reasoningItems[i].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content
            assertEquals("thinking trace $i", text)
        }
    }

    @Test
    fun `tool output truncated only when overflow`() {
        // [L3] 照抄 opencode：仅当估算总 token ≥ context−20K（overflow）时，
        // 非最近 2 轮的超长工具输出截断 2500 + [truncated]；最近 2 轮始终完整
        // 构造 5 轮工具，每轮输出 100K 字符（25K tokens）→ 总 125K ≥ 120K → overflow
        val bigOutput = "x".repeat(100_000)
        val msgs = mutableListOf<UIMessage>()
        for (i in 1..5) {
            msgs.add(UIMessage.user("round $i"))
            msgs.add(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolName = "shell",
                            toolCallId = "call_$i",
                            input = "cat file$i",
                            output = listOf(UIMessagePart.Text(bigOutput))
                        )
                    )
                )
            )
        }
        val items = buildMessages(msgs)
        val fcos = items.jsonArray.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" }
        assertEquals(5, fcos.size)
        // 第 1、2 轮：累计估算超 40K → prune 清空（优先级高于截断）
        for (i in 0 until 2) {
            val out = fcos[i].jsonObject["output"]?.jsonPrimitive?.content
            assertEquals("[Old tool result content cleared]", out)
        }
        // 第 3 轮：非最近 2 轮、未触发 prune、overflow → 截断
        val third = fcos[2].jsonObject["output"]?.jsonPrimitive?.content
        assertTrue(third != null && third!!.length <= 2500 + "\n[truncated]".length)
        assertTrue(third!!.endsWith("[truncated]"))
        // 第 4、5 轮：最近 2 轮 → 完整
        for (i in 3 until 5) {
            val out = fcos[i].jsonObject["output"]?.jsonPrimitive?.content
            assertEquals(100_000, out?.length)
        }
        // 短输出永不截断（即使 overflow）
        val msgs2 = mutableListOf<UIMessage>()
        msgs2.add(UIMessage.user("q"))
        msgs2.add(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolName = "shell",
                        toolCallId = "call_s",
                        input = "echo hi",
                        output = listOf(UIMessagePart.Text("hi"))
                    )
                )
            )
        )
        val items2 = buildMessages(msgs2)
        val fco2 = items2.jsonArray.first { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" }
        assertEquals("hi", fco2.jsonObject["output"]?.jsonPrimitive?.content)
    }

    @Test
    fun `old tool outputs cleared when exceeding prune budget`() {
        // [L3] 照抄 opencode prune()：最近 2 轮保护；更早工具输出估算累计超 40K tokens
        // 的部分清空为 [Old tool result content cleared]；清理量 > 20K 才应用。
        // 构造 4 轮，每轮 200K 字符（50K tokens）→ 轮3+轮2+轮1 累计 150K > 40K → 清空
        val bigOutput = "y".repeat(200_000)
        val msgs = mutableListOf<UIMessage>()
        for (i in 1..4) {
            msgs.add(UIMessage.user("round $i"))
            msgs.add(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolName = "shell",
                            toolCallId = "call_$i",
                            input = "cat file$i",
                            output = listOf(UIMessagePart.Text(bigOutput))
                        )
                    )
                )
            )
        }
        val items = buildMessages(msgs)
        val fcos = items.jsonArray.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" }
        assertEquals(4, fcos.size)
        // 轮1、轮2：累计估算超 40K，清理量 100K tokens > 20K → 清空
        for (i in 0 until 2) {
            val out = fcos[i].jsonObject["output"]?.jsonPrimitive?.content
            assertEquals("[Old tool result content cleared]", out)
        }
        // 轮3、轮4：最近 2 轮保护 → 完整
        for (i in 2 until 4) {
            val out = fcos[i].jsonObject["output"]?.jsonPrimitive?.content
            assertEquals(200_000, out?.length)
        }
    }

    @Test
    fun `prune not applied when below minimum`() {
        // [L3] prune 阈值：清理量 ≤ 20K tokens 时不应用（opencode PRUNE_MINIMUM）
        // 构造 3 轮，每轮 60K 字符（15K tokens）→ 轮2+轮1 累计 30K ≤ 40K → 不清空
        val bigOutput = "z".repeat(60_000)
        val msgs = mutableListOf<UIMessage>()
        for (i in 1..3) {
            msgs.add(UIMessage.user("round $i"))
            msgs.add(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolName = "shell",
                            toolCallId = "call_$i",
                            input = "cat file$i",
                            output = listOf(UIMessagePart.Text(bigOutput))
                        )
                    )
                )
            )
        }
        val items = buildMessages(msgs)
        val fcos = items.jsonArray.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" }
        assertEquals(3, fcos.size)
        // 无 overflow（总 45K tokens < 108K）→ 全部完整
        for (i in 0 until 3) {
            val out = fcos[i].jsonObject["output"]?.jsonPrimitive?.content
            assertEquals(60_000, out?.length)
        }
    }

    @Test
    fun `same user turn keeps all tool outputs`() {
        // [L3] 对齐 opencode turns()：同一轮（1 个 user 消息段）内多次工具调用全部保留
        val msgs = mutableListOf<UIMessage>()
        msgs.add(UIMessage.user("run a long tool chain"))
        for (i in 1..5) {
            msgs.add(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolName = "shell",
                            toolCallId = "call_$i",
                            input = "cmd $i",
                            output = listOf(UIMessagePart.Text("y".repeat(5000)))
                        )
                    )
                )
            )
        }
        val items = buildMessages(msgs)
        val fcos = items.jsonArray.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" }
        assertEquals(5, fcos.size)
        for (i in 0 until 5) {
            val out = fcos[i].jsonObject["output"]?.jsonPrimitive?.content
            assertEquals(5000, out?.length)
        }
    }

    @Test
    fun `recent two user turns keep full output`() {
        // [L3] 边界：恰好 2 轮（2 个 user 段）时全部保留完整
        val msgs = mutableListOf<UIMessage>()
        msgs.add(UIMessage.user("run"))
        for (i in 1..2) {
            msgs.add(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolName = "shell",
                            toolCallId = "call_$i",
                            input = "cmd $i",
                            output = listOf(UIMessagePart.Text("y".repeat(5000)))
                        )
                    )
                )
            )
            msgs.add(UIMessage.user("continue $i"))
        }
        val items = buildMessages(msgs)
        val fcos = items.jsonArray.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" }
        assertEquals(2, fcos.size)
        for (i in 0 until 2) {
            val out = fcos[i].jsonObject["output"]?.jsonPrimitive?.content
            assertEquals(5000, out?.length)
        }
    }

    @Test
    fun `tool-heavy turns do not evict latest reasoning`() {
        // [L1-FIX] 工具轮次（无 reasoning）不应计入保留窗口：1 轮思考 + 4 轮工具调用
        // 时，唯一/最新思考必须保留完整（旧实现按 assistant 消息计数会把思考挤掉）
        val msgs = mutableListOf<UIMessage>()
        msgs.add(UIMessage.user("research and summarize"))
        msgs.add(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "plan the research"),
                    UIMessagePart.Text("let me search")
                )
            )
        )
        // 4 轮工具调用（assistant 消息，无 reasoning）
        for (i in 1..4) {
            msgs.add(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolName = "shell",
                            toolCallId = "call_$i",
                            input = "cmd $i",
                            output = listOf(UIMessagePart.Text("result $i"))
                        )
                    )
                )
            )
        }
        msgs.add(UIMessage.user("explain your approach"))
        val items = buildMessages(msgs, useReasoningTextArray = true)
        val reasoningItems = items.jsonArray.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }
        // 应只有 1 个 reasoning（思考轮次），且内容完整
        assertEquals(1, reasoningItems.size)
        val text = reasoningItems[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content
        assertEquals("plan the research", text)
    }

    @Test
    fun `plain reasoning content old turns placeholder`() {
        // [L1] DeepSeek 明文路径（usePlainReasoningContent）：overflow 时旧轮次占位符
        val msgs = mutableListOf<UIMessage>()
        for (i in 0 until 5) {
            msgs.add(UIMessage.user("question $i"))
            msgs.add(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Reasoning(reasoning = "thinking trace $i"),
                        UIMessagePart.Text("answer $i")
                    )
                )
            )
        }
        msgs.add(UIMessage.user("x".repeat(500_000)))  // 触发 overflow
        val items = buildMessages(msgs, usePlainReasoningContent = true)
        val reasoningItems = items.jsonArray.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }
        assertEquals(5, reasoningItems.size)
        assertEquals("…", reasoningItems[0].jsonObject["content"]?.jsonPrimitive?.content)
        for (i in 1 until 5) {
            assertEquals(
                "thinking trace $i",
                reasoningItems[i].jsonObject["content"]?.jsonPrimitive?.content
            )
        }
    }

    @Test
    fun `summary branch old turns placeholder without encrypted content`() {
        // [L1] OpenAI 官方 summary 分支：overflow 时旧轮次 summary_text 占位
        val msgs = mutableListOf<UIMessage>()
        for (i in 0 until 5) {
            msgs.add(UIMessage.user("question $i"))
            msgs.add(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Reasoning(reasoning = "thinking trace $i"),
                        UIMessagePart.Text("answer $i")
                    )
                )
            )
        }
        msgs.add(UIMessage.user("x".repeat(500_000)))  // 触发 overflow
        val items = buildMessages(msgs)
        val reasoningItems = items.jsonArray.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }
        assertEquals(5, reasoningItems.size)
        // 最早一轮：占位 + 无 encrypted_content
        val first = reasoningItems[0].jsonObject
        val summaryText = first["summary"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content
        assertEquals("…", summaryText)
        assertNull(first["encrypted_content"])
        // 最近一轮：完整
        val last = reasoningItems[4].jsonObject
        val lastText = last["summary"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content
        assertEquals("thinking trace 4", lastText)
    }

    @Test
    fun `tool output at exactly 2500 chars is not truncated`() {
        // [L3] 边界：恰好 2500 字符不截断
        val exactOutput = "x".repeat(2500)
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolName = "shell",
                    toolCallId = "call_exact",
                    input = "cat file",
                    output = listOf(UIMessagePart.Text(exactOutput))
                )
            )
        )
        val items = buildMessages(listOf(assistant))
        val fco = items.jsonArray.first { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" }
        val output = fco.jsonObject["output"]?.jsonPrimitive?.content
        assertEquals(2500, output?.length)
        assertTrue(!output!!.endsWith("[truncated]"))
    }

    @Test
    fun `opencode host buildRequestBody uses reasoning_text for history`() {
        // 走真实路径：host=opencode.ai 时历史 reasoning item 必须是 reasoning_text 数组
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "thinking trace"),
                UIMessagePart.Text("answer")
            )
        )
        val body = buildRequestBody(
            openCodeSetting(),
            listOf(UIMessage.user("next"), assistant),
            reasoningParams(ReasoningLevel.HIGH),
            stream = false
        )
        val input = body["input"]?.jsonArray
        assertTrue(input != null)
        val reasoningItem = input!!.first { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }
        val content = reasoningItem.jsonObject["content"]?.jsonArray
        assertTrue(content != null && content.isNotEmpty())
        assertEquals("reasoning_text", content!![0].jsonObject["type"]?.jsonPrimitive?.content)
        assertNull(reasoningItem.jsonObject["summary"])
    }

    @Test
    fun `opencode tool message without captured reasoning gets placeholder reasoning item`() {
        // Console Go 网关 thinking mode：带工具调用的 assistant 消息必须回传非空 reasoning_text
        // （错误：The reasoning_text in the thinking mode must be passed back to the API）。
        // 历史消息未捕获思维链时用占位符补上，且 reasoning item 必须在 function_call 之前。
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text(""),
                UIMessagePart.Tool(
                    toolCallId = "call_1",
                    toolName = "add",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("2")),
                )
            )
        )
        val body = buildRequestBody(
            openCodeSetting(),
            listOf(UIMessage.user("1+1=?"), assistant),
            reasoningParams(ReasoningLevel.XHIGH),
            stream = false
        )
        val input = body["input"]?.jsonArray!!
        val reasoningIdx = input.indexOfFirst { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }
        val fcIdx = input.indexOfFirst { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call" }
        assertTrue(reasoningIdx >= 0)
        assertTrue(reasoningIdx < fcIdx)
        val text = input[reasoningIdx].jsonObject["content"]?.jsonArray
            ?.getOrNull(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
        assertEquals("…", text)
    }

    @Test
    fun `opencode blank reasoning part is replaced by placeholder`() {
        // 思维链 part 存在但文本为空时，网关同样拒绝（空 reasoning_text 实测 400），
        // useReasoningTextArray 分支必须用占位符兜底
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = ""),
                UIMessagePart.Text("answer"),
                UIMessagePart.Tool(
                    toolCallId = "call_1",
                    toolName = "add",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("2")),
                )
            )
        )
        val body = buildRequestBody(
            openCodeSetting(),
            listOf(UIMessage.user("1+1=?"), assistant),
            reasoningParams(ReasoningLevel.HIGH),
            stream = false
        )
        val input = body["input"]?.jsonArray!!
        val reasoningItem = input.first { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }
        val text = reasoningItem.jsonObject["content"]?.jsonArray
            ?.getOrNull(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
        assertEquals("…", text)
    }

    @Test
    fun `opencode multiple tool calls each get their own reasoning item`() {
        // [FIX] 网关按 function_call 逐个校验 reasoning：1 个 reasoning 只服务其后的
        // 第 1 个 fc。同一条 assistant 消息里 2 个并行工具（模型一轮输出 1 个思维链 +
        // 2 个 tool_calls）必须为第 2 个 fc 补占位 reasoning，否则继续生成请求
        // （input 以 function_call_output 结尾）400「reasoning_text must be passed back」。
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "let me think"),
                UIMessagePart.Tool(
                    toolCallId = "call_1",
                    toolName = "background_task",
                    input = """{"action":"list_tasks"}""",
                    output = listOf(UIMessagePart.Text("{}")),
                ),
                UIMessagePart.Tool(
                    toolCallId = "call_2",
                    toolName = "get_time_info",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("{}")),
                ),
            )
        )
        val body = buildRequestBody(
            openCodeSetting(),
            listOf(UIMessage.user("你好"), assistant),
            reasoningParams(ReasoningLevel.XHIGH),
            stream = false
        )
        val input = body["input"]?.jsonArray!!
        val types = input.map { it.jsonObject["type"]?.jsonPrimitive?.content ?: it.jsonObject["role"]?.jsonPrimitive?.content }
        // 期望顺序：user, reasoning(真实), fc1, fco1, reasoning(占位), fc2, fco2
        assertEquals("user", types[0])
        assertEquals("reasoning", types[1])
        assertEquals("function_call", types[2])
        assertEquals("function_call_output", types[3])
        assertEquals("reasoning", types[4])
        assertEquals("function_call", types[5])
        assertEquals("function_call_output", types[6])
        // 第二个 fc 前的 reasoning 是占位符
        val placeholder = input[4].jsonObject["content"]?.jsonArray
            ?.getOrNull(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
        assertEquals("…", placeholder)
        // 两个 fc 的 call_id 保持正确
        assertEquals("call_1", input[2].jsonObject["call_id"]?.jsonPrimitive?.content)
        assertEquals("call_2", input[5].jsonObject["call_id"]?.jsonPrimitive?.content)
    }
}
