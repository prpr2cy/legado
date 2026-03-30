package io.legado.app.help

import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.ConcurrentException
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * 并发访问限流器
 * 每个实例绑定一个特定的源，用于控制该源的并发访问
 * 支持两种并发控制模式：
 * 1. 间隔模式："100" 表示每次访问间隔至少 100ms（单线程排队）
 * 2. 频率模式："5/1000" 表示 1000ms 内最多允许 5 次访问（多线程计数）
 */
class ConcurrentRateLimiter(source: BaseSource?) {

    companion object {
        val concurrentRecordMap = ConcurrentHashMap<String, ConcurrentRecord>()

        fun remove(sourceKey: String) {
            concurrentRecordMap.remove(sourceKey)
        }

        fun clear() {
            concurrentRecordMap.clear()
        }
    }

    private val concurrentRate = source?.concurrentRate
    private val sourceKey = source?.getKey()

    /**
     * 并发记录实体
     */
    data class ConcurrentRecord(
        // 开始访问时间
        var time: Long,
        // 限制次数
        var accessLimit: Int,
        // 间隔时间
        var interval : Int,
        // 正在访问的个数
        var frequency: Int
    )

    /**
     * 尝试获取访问许可（非阻塞）
     */
    @Throws(ConcurrentException::class)
    private fun fetchStart(): ConcurrentRecord? {
        if (concurrentRate.isNullOrEmpty() || concurrentRate == "0" || sourceKey == null) {
            return null
        }

        var isNewRecord = false
        val fetchRecord = concurrentRecordMap.computeIfAbsent(sourceKey) {
            isNewRecord = true
            val rateIndex = concurrentRate.indexOf("/")
            if (rateIndex > 0) {
                val accessLimit = concurrentRate.take(rateIndex).toIntOrNull() ?: 1
                val interval = concurrentRate.substring(rateIndex + 1).toIntOrNull() ?: 0
                // 初始frequency为0，获取许可后才+1
                ConcurrentRecord(System.currentTimeMillis(), accessLimit, interval, 0)
            } else {
                val interval = concurrentRate.toIntOrNull() ?: 0
                // 初始frequency为0，获取许可后才+1
                ConcurrentRecord(System.currentTimeMillis(), 1, interval, 0)
            }
        }

        if (isNewRecord) {
            // 新记录不等待直接获取
            fetchRecord.frequency = 1
            return fetchRecord
        }

        val waitTime: Long = synchronized(fetchRecord) {

            // 并发控制为 次数/毫秒 , 非并发实际为1/毫秒
            val nextTime = fetchRecord.time + fetchRecord.interval.toLong()
            val nowTime = System.currentTimeMillis()

            if (nowTime >= nextTime) {
                // 已经过了限制时间，重置
                fetchRecord.time = nowTime
                fetchRecord.frequency = 1  // 重置后为当前请求计数
                return@synchronized 0
            }

            // 间隔模式
            if (fetchRecord.accessLimit == 1) {
                // 间隔模式下，frequency表示正在进行的请求数
                // 如果有请求正在进行，需要等待
                if (fetchRecord.frequency > 0) {
                    return@synchronized nextTime - nowTime
                }
                // 没有请求在进行，可以执行，增加计数
                fetchRecord.frequency = 1
                return@synchronized 0
            }

            // 频率模式
            if (fetchRecord.frequency < fetchRecord.accessLimit) {
                fetchRecord.frequency++
                return@synchronized 0
            } else {
                return@synchronized nextTime - nowTime
            }
        }

        if (waitTime > 0) {
            throw ConcurrentException(
                "根据并发率还需等待${waitTime}毫秒才可以访问",
                waitTime = waitTime
            )
        }

        return fetchRecord
    }

    /**
     * 释放访问许可（只在间隔模式下需要）
     */
    fun fetchEnd(record: ConcurrentRecord?) {
        if (record != null && record.accessLimit == 1) {
            synchronized(record) {
                // 确保不会减到负数
                record.frequency = (record.frequency - 1).coerceAtLeast(0)
            }
        }
    }

    /**
     * 获取并发记录，若处于限流状态则自动等待
     */
    suspend fun getConcurrentRecord(): ConcurrentRecord? {
        while (true) {
            try {
                return fetchStart()
            } catch (e: ConcurrentException) {
                delay(e.waitTime)
            }
        }
    }

    /**
     * 获取并发记录（同步版本，会阻塞调用）
     */
    fun getConcurrentRecordBlocking(): ConcurrentRecord? {
        while (true) {
            try {
                return fetchStart()
            } catch (e: ConcurrentException) {
                Thread.sleep(e.waitTime)
            }
        }
    }

    /**
     * 并发控制扩展函数
     */
    suspend inline fun <T> withLimit(block: () -> T): T {
        val record = getConcurrentRecord()
        return try {
            block()
        } finally {
            fetchEnd(record)
        }
    }

    /**
     * 并发控制扩展函数（同步版本）
     */
    inline fun <T> withLimitBlocking(block: () -> T): T {
        val record = getConcurrentRecordBlocking()
        return try {
            block()
        } finally {
            fetchEnd(record)
        }
    }

    /**
     * 清理当前源的并发记录
     */
    fun remove() {
        if (sourceKey != null) {
            concurrentRecordMap.remove(sourceKey)
        }
    }

}