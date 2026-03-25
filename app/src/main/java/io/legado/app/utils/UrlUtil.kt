package io.legado.app.utils

import io.legado.app.BuildConfig
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.semicolonRegex
import io.legado.app.help.config.AppConfig
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.CustomUrl
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

object UrlUtil {

    // 有时候文件名在query里，截取path会截到其他内容
    // https://www.example.com/download.php?filename=文件.txt
    // https://www.example.com/txt/文件.txt?token=123456
    private val unExpectFileSuffixs = arrayOf(
        "php", "html"
    )

    fun replaceReservedChar(text: String): String {
        return text.replace("%", "%25")
            .replace(" ", "%20")
            .replace("\"", "%22")
            .replace("#", "%23")
            .replace("&", "%26")
            .replace("(", "%28")
            .replace(")", "%29")
            .replace("+", "%2B")
            .replace(",", "%2C")
            .replace("/", "%2F")
            .replace(":", "%3A")
            .replace(";", "%3B")
            .replace("<", "%3C")
            .replace("=", "%3D")
            .replace(">", "%3E")
            .replace("?", "%3F")
            .replace("@", "%40")
            .replace("\\", "%5C")
            .replace("|", "%7C")
    }


    /* 阅读定义的url,{urlOption} */
    fun getFileName(analyzeUrl: AnalyzeUrl): String? {
        return getFileName(analyzeUrl.url, analyzeUrl.headerMap)
    }

    /**
     * 根据网络url获取文件信息 文件名
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun getFileName(fileUrl: String, headerMap: Map<String, String>? = null): String? {
        return kotlin.runCatching {
            val url = URL(fileUrl)
            var fileName: String? = getFileNameFromPath(url)
            if (fileName == null) {
                fileName = getFileNameFromResponseHeader(url, headerMap)
            }
            fileName
        }.getOrNull()
    }

    @Suppress("MemberVisibilityCanBePrivate")
    private fun getFileNameFromResponseHeader(
        url: URL,
        headerMap: Map<String, String>? = null
    ): String? {
        // HEAD方式获取链接响应头信息
        val conn: HttpURLConnection = url.openConnection() as HttpURLConnection
        conn.requestMethod = "HEAD"
        // 下载链接可能还需要header才能成功访问
        headerMap?.forEach { (key, value) ->
            conn.setRequestProperty(key, value)
        }
        // 禁止重定向 否则获取不到响应头返回的Location
        conn.instanceFollowRedirects = false
        conn.connect()

        if (AppConfig.recordLog || BuildConfig.DEBUG) {
            val headers = conn.headerFields
            val headersString = buildString {
                headers.forEach { (key, value) ->
                   value.forEach {
                       append(key)
                       append(": ")
                       append(it)
                       append("\n")
                   }
               }
            }
            AppLog.put("$url response header:\n$headersString")
        }

        // val fileSize = conn.getContentLengthLong() / 1024
        /** Content-Disposition 存在三种情况 文件名应该用引号 有些用空格
         * filename="filename"
         * filename*="charset''filename"
         */
        val raw: String? = conn.getHeaderField("Content-Disposition")
        // Location跳转到实际链接
        val redirectUrl: String? = conn.getHeaderField("Location")

        return if (raw != null) {
            val fileNames = raw.split(semicolonRegex).filter { it.contains("filename") }
            val names = hashSetOf<String>()
            fileNames.forEach {
                val fileName = it.substringAfter("=")
                    .trim()
                    .replace("^\"".toRegex(), "")
                    .replace("\"$".toRegex(), "")
                if (it.contains("filename*")) {
                    val data = fileName.split("''")
                    names.add(URLDecoder.decode(data[1], data[0]))
                } else {
                    names.add(fileName)
                    /* 好像不用这样
                    names.add(
                            String(
                            fileName.toByteArray(StandardCharsets.ISO_8859_1),
                            StandardCharsets.UTF_8
                        )
                    )
                    */
                }
           }
           names.firstOrNull()
        } else if (redirectUrl != null) {
            val newUrl= URL(URLDecoder.decode(redirectUrl, "UTF-8"))
            getFileNameFromPath(newUrl)
        } else {
            AppLog.put("Cannot obtain URL file name, enable recordLog for response header")
            null
        }
    }

    private fun getFileNameFromPath(fileUrl: URL): String? {
        val path = fileUrl.path ?: return null
        val suffix = getSuffix(path, "")
        return if (
           suffix != "" && !unExpectFileSuffixs.contains(suffix)
        ) {
            path.substringAfterLast("/")
        } else {
            AppLog.put("getFileNameFromPath: Unexpected file suffix: $suffix")
            null
        }
    }

    // MIME 扩展名映射
    private val MIME_TO_EXT = mapOf(
        // 图片
        "image/jpg" to "jpg",
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/x-png" to "png",
        "image/icon" to "ico",
        "image/x-icon" to "ico",
        "image/bmp" to "bmp",
        "image/gif" to "gif",
        "image/avif" to "avif",
        "image/heic" to "heic",
        "image/heif" to "heif",
        "image/tiff" to "tiff",
        "image/x-tiff" to "tiff",
        "image/apng" to "apng",
        "image/webp" to "webp",
        "image/svg+xml" to "svg",

        // 音频
        "audio/mpeg" to "mp3",
        "audio/x-mpeg" to "mp3",
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
        "audio/aac" to "aac",
        "audio/ogg" to "ogg",
        "audio/mp4" to "m4a",
        "audio/x-m4a" to "m4a",
        "audio/flac" to "flac",
        "audio/x-flac" to "flac",
        "audio/webm" to "weba",

        // 视频
        "video/mp4" to "mp4",
        "video/mpeg" to "mpeg",
        "video/webm" to "webm",
        "video/ogg" to "ogv",
        "video/x-msvideo" to "avi",
        "video/quicktime" to "mov",

        // 文档
        "text/plain" to "txt",
        "text/xml" to "xml",
        "text/html" to "html",
        "text/javascript" to "js",
        "text/css" to "css",
        "text/markdown" to "md",
        "application/pdf" to "pdf",
        "application/json" to "json",
        "application/xml" to "xml",
        "application/javascript" to "js",
        "application/zip" to "zip",
        "application/gzip" to "gz",
        "application/x-tar" to "tar",
        "application/octet-stream" to "bin",
        "application/xhtml+xml" to "xhtml"
    )

    private val dataUriSuffixRegex = Regex("data:([^;,]+)", RegexOption.IGNORE_CASE)

    private val fileSuffixRegex = Regex("^[a-z\\d]+$", RegexOption.IGNORE_CASE)

    /* 获取合法的文件后缀 */
    fun getSuffix(str: String, default: String? = null): String {
        val suffix = str.let { input ->
            if (dataUriSuffixRegex.containsMatchIn(input)) {
                val mimeType = dataUriSuffixRegex.find(input)
                    ?.groupValues?.get(1)
                    ?.lowercase() ?: ""
                MIME_TO_EXT[mimeType]
                    ?: mimeType.substringAfterLast("/")
                        .substringBefore("+")
                        .substringAfterLast("-", "")
            } else {
                CustomUrl(input).getUrl()
                    .substringBefore("?")
                    .substringAfterLast("/")
                    .substringBefore("#")
                    .substringAfterLast(".", "")
                    .lowercase()
            }
        }
        //检查截取的后缀字符是否合法 [a-zA-Z0-9]
        return if (suffix.length > 5 || !suffix.matches(fileSuffixRegex)) {
            if (default == null) {
                AppLog.put("Cannot find legal suffix:\n target: $str\n suffix: $suffix")
            }
            default ?: "ext"
        } else {
            suffix
        }
    }

}
