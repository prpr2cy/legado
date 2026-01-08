package io.legado.app.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Size
import androidx.collection.LruCache
import io.legado.app.R
import io.legado.app.constant.AppLog.putDebug
import io.legado.app.constant.PageAnim
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isPdf
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.PdfFile
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.util.Collections

object ImageProvider {

    private val errorBitmap: Bitmap by lazy {
        BitmapFactory.decodeResource(appCtx.resources, R.drawable.image_loading_error)
    }

    // 解码失败的记录
    private val decodeFailedCache = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * 缓存bitmap LruCache实现
     * filePath bitmap
     */
    private const val M = 1024 * 1024
    val cacheSize: Int
        get() {
            if (AppConfig.bitmapCacheSize <= 0) {
                AppConfig.bitmapCacheSize = 50
            }
            return AppConfig.bitmapCacheSize * M
        }
    var triggerRecycled = false
    val bitmapLruCache = object : LruCache<String, Bitmap>(cacheSize) {

        override fun sizeOf(filePath: String, bitmap: Bitmap): Int {
            return bitmap.byteCount
        }

        override fun entryRemoved(
            evicted: Boolean,
            filePath: String,
            oldBitmap: Bitmap,
            newBitmap: Bitmap?
        ) {
            runCatching {
                if (!oldBitmap.isRecycled) {
                    oldBitmap.recycle()
                    triggerRecycled = true
                    //putDebug("ImageProvider: trigger bitmap recycle. URI: $filePath")
                    //putDebug("ImageProvider : cacheUsage ${size()}bytes / ${maxSize()}bytes")
                }
            }
        }
    }

    fun put(key: String, bitmap: Bitmap) {
        bitmapLruCache.put(key, bitmap)
    }

    fun get(key: String): Bitmap? {
        return bitmapLruCache.get(key)
    }

    private fun getNotRecycled(key: String): Bitmap? {
        val bitmap = bitmapLruCache.get(key) ?: return null
        if (bitmap.isRecycled) {
            bitmapLruCache.remove(key)
            return null
        }
        return bitmap
    }

    /**
     *缓存网络图片和epub图片
     */
    suspend fun cacheImage(
        book: Book,
        src: String,
        bookSource: BookSource?
    ): File {
        return withContext(IO) {
            val vFile = BookHelp.getImage(book, src)
            if (!vFile.exists()) {
                if (book.isEpub) {
                    EpubFile.getImage(book, src)?.use { input ->
                        val newFile = FileUtils.createFileIfNotExist(vFile.absolutePath)
                        FileOutputStream(newFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } else if (book.isPdf) {
                    PdfFile.getImage(book, src)?.use { input ->
                        val newFile = FileUtils.createFileIfNotExist(vFile.absolutePath)
                        FileOutputStream(newFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } else {
                    BookHelp.saveImage(bookSource, book, src)
                }
            }
            return@withContext vFile
        }
    }

    /**
     *获取图片宽度高度信息
     */
    suspend fun getImageSize(
        book: Book,
        src: String,
        bookSource: BookSource?
    ): Size {
        val file = cacheImage(book, src, bookSource)
        val op = BitmapFactory.Options()
        // inJustDecodeBounds如果设置为true,仅仅返回图片实际的宽和高,宽和高是赋值给opts.outWidth,opts.outHeight;
        op.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, op)
        if (op.outWidth < 1 && op.outHeight < 1) {
            //svg size
            val size = SvgUtils.getSize(file.absolutePath)
            if (size != null) return size
            putDebug("ImageProvider: $src Unsupported image type")
            //file.delete() 重复下载
            return Size(errorBitmap.width, errorBitmap.height)
        }
        return Size(op.outWidth, op.outHeight)
    }

    /**
     *获取bitmap 使用LruCache缓存
     */
    fun getImage(
        book: Book,
        src: String,
        width: Int,
        height: Int? = null,
        block: (() -> Unit)? = null
    ): Bitmap? {
        AppLog.put("宽：${width}px 高：${height}px 地址：${src}")
        //src为空白时 可能被净化替换掉了 或者规则失效
        if (book.getUseReplaceRule() && src.isBlank()) {
            appCtx.toastOnUi(R.string.error_image_url_empty)
            return errorBitmap
        }
        val vFile = BookHelp.getImage(book, src)
        if (!vFile.exists()) return errorBitmap
        //epub文件提供图片链接是相对链接，同时阅读多个epub文件，缓存命中错误
        //bitmapLruCache的key同一改成缓存文件的路径

        val cacheKey = getCacheKey(vFile.absolutePath, width, height)
        // 检查解码失败记录
        if (decodeFailedCache.contains(cacheKey)) return errorBitmap

        val cacheBitmap = getNotRecycled(cacheKey)
        if (cacheBitmap != null) return cacheBitmap

        if (height != null && AppConfig.asyncLoadImage && ReadBook.pageAnim() == PageAnim.scrollPageAnim) {
            Coroutine.async {
                val bitmap = BitmapUtils.decodeBitmap(vFile.absolutePath, width, height)
                    ?: SvgUtils.createBitmap(vFile.absolutePath, width, height)
                    ?: throw NoStackTraceException(appCtx.getString(R.string.error_decode_bitmap))
                withContext(Main) {
                    bitmapLruCache.put(cacheKey, bitmap)
                }
            }.onError {
                // 记录解码失败
                decodeFailedCache.add(cacheKey)
            }.onFinally {
                block?.invoke()
            }
            return null
        }

        return kotlin.runCatching {
            val bitmap = BitmapUtils.decodeBitmap(vFile.absolutePath, width, height)
                ?: SvgUtils.createBitmap(vFile.absolutePath, width, height)
                ?: throw NoStackTraceException(appCtx.getString(R.string.error_decode_bitmap))
            bitmapLruCache.put(cacheKey, bitmap)
            bitmap
        }.onFailure {
            // 记录解码失败
            decodeFailedCache.add(cacheKey)
        }.getOrDefault(errorBitmap)
    }

    fun getCacheKey(filePath: String, width: Int? = null, height: Int? = null): String {
        return if (width != null && height != null) {
            "${filePath}_w${width}_h${height}"
        } else filePath
    }

    /**
     * 清理与同一文件路径相关的所有缓存（忽略 _wXXX_hXXX 后缀）
     */
    fun clearCache(filePath: String) {
        // 快照，防止遍历时并发修改 
        val keysToRemove = mutableListOf<String>()
        synchronized(bitmapLruCache) {
            for (key in bitmapLruCache.snapshot().keys) {
                if (key.startsWith(filePath)) {
                    keysToRemove += key 
                }
            }
        }
        // 统一删，触发 entryRemoved 回收 bitmap 
        keysToRemove.forEach { bitmapLruCache.remove(it) }
    }

    fun isImageAlive(book: Book, src: String, width: Int? = null, height: Int? = null): Boolean {
        val vFile = BookHelp.getImage(book, src)
        if (!vFile.exists()) return true // 使用 errorBitmap
        val cacheKey = getCacheKey(vFile.absolutePath, width, height)
        val cacheBitmap = bitmapLruCache.get(cacheKey)
        return cacheBitmap != null
    }

    fun isTriggerRecycled(): Boolean {
        val tmp = triggerRecycled
        triggerRecycled = false
        return tmp
    }

}