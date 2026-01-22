package io.legado.app.utils

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import io.legado.app.constant.AppLog
import java.lang.ref.WeakReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.max
import kotlin.math.min

@PublishedApi
internal val IS_ANDROID_8 = Build.VERSION.SDK_INT in 26..27

@PublishedApi
internal const val STEP = 500

@PublishedApi
internal const val DELAY_MS = 10L

@PublishedApi
internal const val BURST_SIZE = 3

@PublishedApi
internal const val INITIAL_ARRAY_SIZE = 128

@PublishedApi
internal val handler = Handler(Looper.getMainLooper())

// 获取原始方法
@PublishedApi
internal val realReplaceMethod by lazy {
    Editable::class.java.getDeclaredMethod("replace", Int::class.java, Int::class.java, CharSequence::class.java)
}

@PublishedApi
internal val realDeleteMethod by lazy {
    Editable::class.java.getDeclaredMethod("delete", Int::class.java, Int::class.java)
}

@PublishedApi
internal val realInsertMethod by lazy {
    Editable::class.java.getDeclaredMethod("insert", Int::class.java, CharSequence::class.java)
}

// 操作类型常量
@PublishedApi
internal const val OP_DELETE = 0

@PublishedApi
internal const val OP_INSERT = 1

// 操作数组
@PublishedApi
internal class OptimizedOperationArray {
    private var types = IntArray(INITIAL_ARRAY_SIZE)
    private var starts = IntArray(INITIAL_ARRAY_SIZE)
    private var ends = IntArray(INITIAL_ARRAY_SIZE)
    private var texts = arrayOfNulls<CharSequence>(INITIAL_ARRAY_SIZE)
    private var size = 0

    fun add(type: Int, start: Int, end: Int, text: CharSequence? = null) {
        if (size >= types.size) {
            expand()
        }
        types[size] = type
        starts[size] = start
        ends[size] = end
        texts[size] = text
        size++
    }

    fun removeFirst(): Quadruple<Int, Int, Int, CharSequence?>? {
        if (size == 0) return null

        val type = types[0]
        val start = starts[0]
        val end = ends[0]
        val text = texts[0]

        if (size > 1) {
            System.arraycopy(types, 1, types, 0, size - 1)
            System.arraycopy(starts, 1, starts, 0, size - 1)
            System.arraycopy(ends, 1, ends, 0, size - 1)
            System.arraycopy(texts, 1, texts, 0, size - 1)
        }

        // 清理最后一个元素
        types[size - 1] = 0
        starts[size - 1] = 0
        ends[size - 1] = 0
        texts[size - 1] = null

        size--
        return Quadruple(type, start, end, text)
    }

    fun isEmpty() = size == 0

    fun clear() {
        for (i in 0 until size) {
            texts[i] = null
        }
        size = 0
    }

    private fun expand() {
        val newSize = types.size * 2
        types = types.copyOf(newSize)
        starts = starts.copyOf(newSize)
        ends = ends.copyOf(newSize)
        texts = texts.copyOf(newSize)
    }
}

// 四元组类
@PublishedApi
internal data class Quadruple<out A, out B, out C, out D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

@PublishedApi
internal class EditableQueue(val editableRef: WeakReference<Editable>) {
    val operations = OptimizedOperationArray()
    var isProcessing = false
    val lock = ReentrantLock()

    fun clear() {
        lock.lock()
        try {
            operations.clear()
            isProcessing = false
        } finally {
            lock.unlock()
        }
    }

    fun isValid(): Boolean {
        return editableRef.get() != null
    }

    fun getEditable(): Editable? {
        return editableRef.get()
    }
}

@PublishedApi
internal val queueMap = mutableMapOf<WeakReference<Editable>, EditableQueue>()

/**
 * 覆盖Editable.replace方法
 */
inline fun Editable.replace(
    start: Int,
    end: Int,
    text: CharSequence
): Editable {
    val actualStart = min(start, end)
    val actualEnd = max(start, end)
    val deleteLength = actualEnd - actualStart
    val insertLength = text.length

    if (!IS_ANDROID_8 || (deleteLength + insertLength) <= STEP) {
        return realReplaceMethod.invoke(this, actualStart, actualEnd, text) as Editable
    }

    val ref = WeakReference(this)
    val queue = synchronized(queueMap) {
        queueMap.getOrPut(ref) {
            EditableQueue(ref)
        }
    }

    if (!queue.lock.tryLock()) {
        AppLog.put("Editable.replace: 获取锁失败，跳过队列操作")
        return this
    }

    try {
        // 先删除后插入
        if (deleteLength > 0) {
            var cur = actualEnd
            while (cur > actualStart) {
                val left = max(actualStart, cur - STEP)
                if (left >= cur) break

                queue.operations.add(OP_DELETE, left, cur)
                cur = left
            }
        }

        if (insertLength > 0) {
            var pos = actualStart
            var i = 0
            while (i < insertLength) {
                val chunkSize = min(STEP, insertLength - i)
                val chunk = if (chunkSize == text.length) {
                    text
                } else {
                    text.subSequence(i, i + chunkSize)
                }

                queue.operations.add(OP_INSERT, pos, pos, chunk)
                pos += chunkSize
                i += chunkSize
            }
        }

        if (!queue.isProcessing) {
            queue.isProcessing = true
            handler.post { processQueue(queue) }
        }
    } catch (e: Exception) {
        AppLog.put("Editable.replace: 添加操作到队列时异常", e)
    } finally {
        queue.lock.unlock()
    }

    return this
}

/**
 * 覆盖Editable.delete方法
 */
inline fun Editable.delete(
    start: Int,
    end: Int
): Editable {
    val actualStart = min(start, end)
    val actualEnd = max(start, end)
    val deleteLength = actualEnd - actualStart

    if (!IS_ANDROID_8 || deleteLength <= STEP) {
        return realDeleteMethod.invoke(this, actualStart, actualEnd) as Editable
    }

    val ref = WeakReference(this)
    val queue = synchronized(queueMap) {
        queueMap.getOrPut(ref) {
            EditableQueue(ref)
        }
    }

    if (!queue.lock.tryLock()) {
        AppLog.put("Editable.delete: 获取锁失败，跳过队列操作")
        return this
    }

    try {
        var cur = actualEnd
        while (cur > actualStart) {
            val left = max(actualStart, cur - STEP)
            if (left >= cur) break

            queue.operations.add(OP_DELETE, left, cur)
            cur = left
        }

        if (!queue.isProcessing) {
            queue.isProcessing = true
            handler.post { processQueue(queue) }
        }
    } catch (e: Exception) {
        AppLog.put("Editable.delete: 添加操作到队列时异常", e)
    } finally {
        queue.lock.unlock()
    }

    return this
}

/**
 * 覆盖Editable.insert方法
 */
inline fun Editable.insert(
    where: Int,
    text: CharSequence
): Editable {
    val insertLength = text.length

    if (!IS_ANDROID_8 || insertLength <= STEP) {
        return realInsertMethod.invoke(this, where, text) as Editable
    }

    val ref = WeakReference(this)
    val queue = synchronized(queueMap) {
        queueMap.getOrPut(ref) {
            EditableQueue(ref)
        }
    }

    if (!queue.lock.tryLock()) {
        AppLog.put("Editable.insert: 获取锁失败，跳过队列操作")
        return this
    }

    try {
        var pos = where
        var i = 0
        while (i < insertLength) {
            val chunkSize = min(STEP, insertLength - i)
            val chunk = if (chunkSize == text.length) {
                text
            } else {
                text.subSequence(i, i + chunkSize)
            }

            queue.operations.add(OP_INSERT, pos, pos, chunk)
            pos += chunkSize
            i += chunkSize
        }

        if (!queue.isProcessing) {
            queue.isProcessing = true
            handler.post { processQueue(queue) }
        }
    } catch (e: Exception) {
        AppLog.put("Editable.insert: 添加操作到队列时异常", e)
    } finally {
        queue.lock.unlock()
    }

    return this
}

/**
 * 处理指定队列（使用burst优化）
 */
@PublishedApi
internal fun processQueue(queue: EditableQueue) {
    if (!queue.isValid()) {
        synchronized(queueMap) {
            val entry = queueMap.entries.find { it.value === queue }
            entry?.let { queueMap.remove(it.key) }
        }
        queue.clear()
        AppLog.put("processQueue: Editable已无效，清理队列")
        return
    }

    if (!queue.lock.tryLock()) {
        handler.postDelayed({ processQueue(queue) }, DELAY_MS)
        return
    }

    try {
        if (queue.operations.isEmpty()) {
            queue.isProcessing = false
            return
        }

        val editable = queue.getEditable() ?: return

        // 执行一个burst（连续执行3个操作）
        var executed = 0

        while (executed < BURST_SIZE && !queue.operations.isEmpty()) {
            val operation = queue.operations.removeFirst() ?: break

            try {
                when (operation.first) {
                    OP_DELETE -> {
                        realDeleteMethod.invoke(editable, operation.second, operation.third)
                    }
                    OP_INSERT -> {
                        realInsertMethod.invoke(editable, operation.second, operation.fourth)
                    }
                }
                executed++
            } catch (e: Exception) {
                AppLog.put("processQueue: 执行操作时异常，类型=${operation.first}, start=${operation.second}, end=${operation.third}", e)
            }
        }

        // 如果还有操作，延迟执行下一个burst
        if (!queue.operations.isEmpty()) {
            handler.postDelayed({ processQueue(queue) }, DELAY_MS)
        } else {
            queue.isProcessing = false
        }
    } catch (e: Exception) {
        AppLog.put("processQueue: 处理队列时发生未预期异常", e)
        queue.isProcessing = false
    } finally {
        if (queue.lock.isHeldByCurrentThread) {
            queue.lock.unlock()
        }
    }
}

/**
 * 清理指定Editable的队列
 */
fun clearEditableQueue(editable: Editable) {
    synchronized(queueMap) {
        val entry = queueMap.entries.find { it.key.get() === editable }
        entry?.let {
            it.value.clear()
            queueMap.remove(it.key)
            AppLog.put("clearEditableQueue: 已清理指定Editable的队列")
        }
    }
}

/**
 * 清理所有队列
 */
fun clearAllQueues() {
    synchronized(queueMap) {
        queueMap.values.forEach { it.clear() }
        queueMap.clear()
        AppLog.put("clearAllQueues: 已清理所有队列")
    }
}