package me.rerere.ai.util

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

interface KeyRoulette {
    fun next(keys: String, providerId: String = ""): String

    companion object {
        fun default(): KeyRoulette = DefaultKeyRoulette()

        /**
         * LRU 轮询，内存缓存 + 延迟落盘
         * 通过 providerId 区分同类型的多个 provider 实例
         */
        fun lru(context: Context): KeyRoulette = LruKeyRoulette(context)
    }
}

private val SPLIT_KEY_REGEX = "[\\s,]+".toRegex()

private fun splitKey(key: String): List<String> {
    return key
        .split(SPLIT_KEY_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

private class DefaultKeyRoulette : KeyRoulette {
    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        return if (keyList.isNotEmpty()) {
            keyList.random()
        } else {
            keys
        }
    }
}

private const val LRU_CACHE_FILE = "lru_key_roulette.json"
private const val EXPIRE_DURATION_MS = 24 * 60 * 60 * 1000L
private const val PERSIST_INTERVAL = 10

private typealias LruCache = Map<String, Map<String, Long>>

private class LruKeyRoulette(
    private val context: Context,
) : KeyRoulette {
    private var memoryCache: LruCache? = null
    private var dirty = false
    private var opsSincePersist = 0

    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        if (keyList.isEmpty()) return keys

        synchronized(this) {
            val now = System.currentTimeMillis()
            val allCache = getOrLoadCache().toMutableMap()

            val providerCache = (allCache[providerId] ?: emptyMap())
                .filter { (k, lastUsed) -> k in keyList && now - lastUsed < EXPIRE_DURATION_MS }
                .toMutableMap()

            val selected = keyList.firstOrNull { it !in providerCache }
                ?: providerCache.minByOrNull { it.value }!!.key

            providerCache[selected] = now
            allCache[providerId] = providerCache
            memoryCache = allCache
            dirty = true

            opsSincePersist++
            if (opsSincePersist >= PERSIST_INTERVAL) {
                persistCache(allCache)
                dirty = false
                opsSincePersist = 0
            }

            return selected
        }
    }

    private fun getOrLoadCache(): LruCache {
        if (memoryCache != null) return memoryCache!!
        memoryCache = loadCacheFromDisk()
        return memoryCache!!
    }

    private fun loadCacheFromDisk(): LruCache {
        return try {
            val file = File(context.cacheDir, LRU_CACHE_FILE)
            if (!file.exists()) return emptyMap()
            Json.decodeFromString(file.readText())
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun persistCache(cache: LruCache) {
        try {
            File(context.cacheDir, LRU_CACHE_FILE).writeText(Json.encodeToString(cache))
        } catch (_: Exception) {
        }
    }

    fun flush() {
        synchronized(this) {
            if (dirty && memoryCache != null) {
                persistCache(memoryCache!!)
                dirty = false
            }
        }
    }
}
