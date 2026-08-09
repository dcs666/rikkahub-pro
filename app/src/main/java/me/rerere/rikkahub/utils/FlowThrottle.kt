package me.rerere.rikkahub.utils

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

// 哨兵：CONFLATED channel 不允许 send(null)，用哨兵对象表示 null 值（与 kotlinx 官方一致）
private val NULL = Any()

/**
 * 节流取最新值的 Flow 操作符（替代 [kotlinx.coroutines.flow.sample]）。
 *
 * 节流语义与 sample 相同：每 [periodMillis] 发出最近的一个值，窗口内中间值丢弃。
 *
 * 与官方 sample 的关键差异：**上游完成后最后一个未发出的值必达**。
 * kotlinx 官方 sample 的 KDoc 明确写着 "the latest element is not emitted if it
 * does not fit into the sampling window"——上游完成瞬间（channel close）select 立即
 * 退出循环，采样窗口内尚未发出的最后值被静默丢弃。在流式生成场景（ChatService）中
 * 表现为：回复末尾 32ms 窗口内的 chunk 丢失 → UI 尾部消失、onSuccess 持久化缺尾部
 * 版本（#1296，重启后回复永久截断）。
 *
 * 实现：必须用 [channelFlow]（而非 flow + coroutineScope）——flow builder 的 emit
 * 只能由收集协程调用，从 launch 子协程 emit 会触发 SafeCollector 的
 * "Flow invariant is violated: Emission from another coroutine is detected"
 * （SafeCollector.common.kt 检查）；channelFlow 的 send 允许任意子协程调用。
 *
 * producer 协程收集上游值到 CONFLATED channel（只保留最新），consumer 协程
 * 每周期取出最新值发出；producer 结束（channel close）后，consumer 把剩余的最后一个
 * 值 flush 发出再退出。取消时随 channelFlow scope 一并取消。
 */
internal fun <T> Flow<T>.throttleLatest(periodMillis: Long): Flow<T> = channelFlow {
    // 显式捕获 send：launch 子协程内没有隐式 ProducerScope receiver（与 emit 同理）
    val sendValue: suspend (T) -> Unit = { value -> send(value) }
    val values = Channel<Any?>(Channel.CONFLATED)
    val producer = launch {
        try {
            collect { value -> values.send(value ?: NULL) }
        } finally {
            values.close()
        }
    }
    val consumer = launch {
        var lastValue: Any? = null
        while (true) {
            val result = values.receiveCatching()
            if (result.isSuccess) {
                lastValue = result.getOrNull()
                // 等一个周期再发出，让窗口内更新的值有机会覆盖
                delay(periodMillis)
                val toEmit = lastValue
                lastValue = null
                if (toEmit != null) sendValue(toEmit.unboxNull())
            } else {
                // 上游已结束：flush 最后一个未发出的值，然后退出
                if (lastValue != null) sendValue(lastValue.unboxNull())
                break
            }
        }
    }
    producer.join()
    consumer.join()
}

@Suppress("UNCHECKED_CAST")
private fun <T> Any?.unboxNull(): T =
    if (this === NULL) null as T else this as T
