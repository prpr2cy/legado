package io.legado.app.utils

import io.legado.app.lib.icu4j.CharsetDetector
import org.jsoup.Jsoup
import java.io.File

/**
 * 自动获取文件的编码
 * */
@Suppress("MemberVisibilityCanBePrivate", "unused")
object EncodingDetect {

    private val headTagRegex = "(?i)<head>[\\s\\S]*?</head>".toRegex()
    private val headOpenBytes = "<head>".toByteArray()
    private val headCloseBytes = "</head>".toByteArray()

    private fun ByteArray.indexOfSubArray(first: ByteArray, startIndex: Int = 0): Int {
        if (first.isEmpty()) return 0
        val end = this.size - first.size
        if (end < startIndex) return -1
        for (i in startIndex..end) {
            var j = 0
            while (j < first.size && this[i + j] == first[j]) j++
            if (j == first.size) return i
        }
        return -1
    }

    fun getHtmlEncode(bytes: ByteArray): String {
        try {
            var head: String? = null
            val startIndex = bytes.indexOfSubArray(headOpenBytes)
            if (startIndex > -1) {
                val endIndex = bytes.indexOfSubArray(headCloseBytes, startIndex)
                if (endIndex > -1) {
                    head = String(bytes.copyOfRange(startIndex, endIndex + headCloseBytes.size), Charsets.ISO_8859_1)
                }
            }
            val doc = Jsoup.parseBodyFragment(head ?: headTagRegex.find(String(bytes, Charsets.ISO_8859_1))!!.value)
             doc.getElementsByTag("meta").forEach { meta ->
                 meta.attr("charset").takeIf { it.isNotBlank() }?.let { return it }
                 if (meta.attr("http-equiv").equals("content-type", true)) {
                     val content = meta.attr("content")
                     val idx = content.indexOf("charset=", ignoreCase = true)
                     val cs = if (idx != -1) content.substring(idx + 8) else content.substringAfter(";")
                     if (cs.isNotBlank()) return cs
                 }
             }
        } catch (ignored: Exception) {}
        return getEncode(bytes)
    }

    fun getEncode(bytes: ByteArray): String {
        val match = CharsetDetector().setText(bytes).detect()
        return match?.name ?: "UTF-8"
    }

    /**
     * 得到文件的编码
     */
    fun getEncode(filePath: String): String {
        return getEncode(File(filePath))
    }

    /**
     * 得到文件的编码
     */
    fun getEncode(file: File): String {
        val tempByte = getFileBytes(file)
        if (tempByte.isEmpty()) {
            return "UTF-8"
        }
        return getEncode(tempByte)
    }

    private fun getFileBytes(file: File): ByteArray {
        val result = ByteArray(8000)
        val buffer = ByteArray(8192)
        var pos = 0
        var bytesRead = 0
        try {
            file.inputStream().buffered().use { input ->
                while (pos < result.size && input.read(buffer).also { bytesRead = it } != -1) {
                    for (i in 0 until bytesRead) {
                        if (pos >= result.size) break
                        if (buffer[i] < 0) {  // 只保留非ASCII字符
                            result[pos++] = buffer[i]
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("Error: $e")
        }
        return result.copyOf(pos)
    }
}