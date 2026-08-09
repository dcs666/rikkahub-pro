package me.rerere.rikkahub.utils

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * throttleLatest 语义测试：节流取最新值（每周期最多一个），且上游完成后
 * 最后一个未发出的值必达（与官方 Flow.sample 的关键差异，修复 #1296）。
 */
class FlowThrottleTest {

    @Test
    fun `上游快速完成后最后一个值必达`() = runBlocking {
        // 无 delay 密集 emit：中间值被节流，但最后一个值必须到达
        val result = (1..100).asFlow().throttleLatest(5).toList()
        assertTrue("节流后不应保留全部 100 个值", result.size < 100)
        assertEquals("最后一个值必须到达", 100, result.last())
    }

    @Test
    fun `最后值在关闭前瞬间到达也必达`() = runBlocking {
        val result = flow {
            emit(1)
            delay(3)
            emit(2)
            delay(3)
            emit(3) // 上游紧接着完成，最后值落在采样窗口内
        }.throttleLatest(10).toList()
        assertEquals("最后一个值 3 必须到达", 3, result.last())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `每周期最多发出一个值`() = runBlocking {
        val result = flow {
            emit(1)
            delay(2)
            emit(2)
            delay(2)
            emit(3)
            delay(2)
            emit(4)
        }.throttleLatest(8).toList()
        // 总时长 ~8ms，最多 2 个采样点；4 必达
        assertTrue("节流后数量应小于上游数量", result.size < 4)
        assertEquals(4, result.last())
    }

    @Test
    fun `空流不发出任何值`() = runBlocking {
        val result = emptyList<Int>().asFlow().throttleLatest(5).toList()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `单个值必达`() = runBlocking {
        val result = listOf(42).asFlow().throttleLatest(5).toList()
        assertEquals(listOf(42), result)
    }

    @Test
    fun `空值也必达`() = runBlocking {
        val result = flow<Int?> { emit(null) }.throttleLatest(5).toList()
        assertEquals("null 值也应到达（哨兵处理）", listOf<Int?>(null), result)
    }

    @Test
    fun `慢速流保留全部值`() = runBlocking {
        // 每个值间隔远大于周期 → 每个值都应在独立窗口内发出
        val result = flow {
            emit(1)
            delay(20)
            emit(2)
            delay(20)
            emit(3)
        }.throttleLatest(5).toList()
        assertEquals(listOf(1, 2, 3), result)
    }
}
