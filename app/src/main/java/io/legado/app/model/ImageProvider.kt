package io.legado.app.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Size
import androidx.collection.LruCache
import io.legado.app.R
import io.legado.app.constant.AppLog
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
import kotlin.math.max
import kotlin.math.min

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

    private const val BASE_SIZE = 50 * M

    val cacheSize: Int
        get() {
            val config = AppConfig.bitmapCacheSize
            return if (config <= 0 || config > 2048) {
                AppConfig.bitmapCacheSize = 50
                50 * M
            } else if (config == 2048) {
                Int.MAX_VALUE
            } else config * M
        }

    val maxSize: Int get() = bitmapLruCache.maxSize()

    val nowSize: Int get() = bitmapLruCache.size()

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
                    //AppLog.putDebug("ImageProvider: trigger bitmap recycle. URI: $filePath")
                    //AppLog.putDebug("ImageProvider : cacheUsage ${size()}bytes / ${maxSize()}bytes")
                }
            }
        }
    }

    fun resize(size: Int) {
        if (size > Int.MAX_VALUE - nowSize) {
            bitmapLruCache.evictAll()
            val newSize = max(size + BASE_SIZE, cacheSize)
            AppLog.put("图片缓存达到2048M，已重置为${newSize / M}M")
            bitmapLruCache.resize(newSize)
        } else if (size > cacheSize * 10 - nowSize) {
            bitmapLruCache.evictAll()
            val newSize = max(size + BASE_SIZE, cacheSize)
            AppLog.put("图片缓存超过10倍设置容量，已重置为${newSize / M}M")
            bitmapLruCache.resize(newSize)
        } else {
            val sumSize = size + nowSize
            val newSize = min(sumSize, Int.MAX_VALUE - BASE_SIZE) + BASE_SIZE
            AppLog.put("图片缓存容量不够大，自动扩增至${newSize / M}M")
            bitmapLruCache.resize(newSize)
        }
    }

    fun put(key: String, bitmap: Bitmap) {
        val byteCount = bitmap.byteCount
        if (byteCount > Int.MAX_VALUE - BASE_SIZE) {
            AppLog.put("图片过大${byteCount / M}M，无法缓存")
            return
        } else if (byteCount > maxSize - nowSize) {
            resize(byteCount)
        }
        bitmapLruCache.put(key, bitmap)
    }

    fun get(key: String): Bitmap? {
        return bitmapLruCache.get(key)
    }

    fun remove(key: String): Bitmap? {
        return bitmapLruCache.remove(key)
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
            AppLog.putDebug("ImageProvider: $src Unsupported image type")
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
        //src为空白时 可能被净化替换掉了 或者规则失效
        if (book.getUseReplaceRule() && src.isBlank()) {
            appCtx.toastOnUi(R.string.error_image_url_empty)
            return errorBitmap
        }
        val vFile = BookHelp.getImage(book, src)
        if (!vFile.exists()) return errorBitmap
        //epub文件提供图片链接是相对链接，同时阅读多个epub文件，缓存命中错误
        //bitmapLruCache的key同一改成缓存文件的路径

        // 检查解码失败记录
        if (decodeFailedCache.contains(vFile.absolutePath)) return errorBitmap

        val cacheBitmap = getNotRecycled(vFile.absolutePath)
        if (cacheBitmap != null) return cacheBitmap

        if (height != null && AppConfig.asyncLoadImage && ReadBook.pageAnim() == PageAnim.scrollPageAnim) {
            Coroutine.async {
                val bitmap = BitmapUtils.decodeBitmap(vFile.absolutePath, width, height)
                    ?: SvgUtils.createBitmap(vFile.absolutePath, width, height)
                    ?: throw NoStackTraceException(appCtx.getString(R.string.error_decode_bitmap))
                withContext(Main) {
                    ImageProvider.put(vFile.absolutePath, bitmap)
                }
            }.onError {
                // 记录解码失败
                decodeFailedCache.add(vFile.absolutePath)
            }.onFinally {
                block?.invoke()
            }
            return null
        }

        return kotlin.runCatching {
            val bitmap = BitmapUtils.decodeBitmap(vFile.absolutePath, width, height)
                ?: SvgUtils.createBitmap(vFile.absolutePath, width, height)
                ?: throw NoStackTraceException(appCtx.getString(R.string.error_decode_bitmap))
            ImageProvider.put(vFile.absolutePath, bitmap)
            bitmap
        }.onFailure {
            // 记录解码失败
            decodeFailedCache.add(vFile.absolutePath)
        }.getOrDefault(errorBitmap)
    }

    fun isImageAlive(book: Book, src: String): Boolean {
        val vFile = BookHelp.getImage(book, src)
        if (!vFile.exists()) return true // 使用errorBitmap
        val cacheBitmap = bitmapLruCache.get(vFile.absolutePath)
        return cacheBitmap != null
    }

    fun isTriggerRecycled(): Boolean {
        val tmp = triggerRecycled
        triggerRecycled = false
        return tmp
    }

}