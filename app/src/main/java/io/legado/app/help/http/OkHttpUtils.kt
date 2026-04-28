package io.legado.app.help.http

import com.github.luben.zstd.ZstdInputStream
import io.legado.app.utils.EncodingDetect
import io.legado.app.utils.GSON
import io.legado.app.utils.Utf8BomUtils
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.brotli.dec.BrotliInputStream

suspend fun OkHttpClient.newCallResponse(
    retry: Int = 0,
    builder: Request.Builder.() -> Unit
): Response {
    val requestBuilder = Request.Builder()
    requestBuilder.apply(builder)
    var response: Response? = null
    for (i in 0..retry) {
        response = newCall(requestBuilder.build()).await()
        if (response.isSuccessful) {
            return response
        }
    }
    return response!!
}

suspend fun OkHttpClient.newCallResponseBody(
    retry: Int = 0,
    builder: Request.Builder.() -> Unit
): ResponseBody {
    return newCallResponse(retry, builder).let {
        it.body ?: throw IOException(it.message)
    }
}

suspend fun OkHttpClient.newCallStrResponse(
    retry: Int = 0,
    builder: Request.Builder.() -> Unit
): StrResponse {
    return newCallResponse(retry, builder).let {
        StrResponse(it, it.body?.text(response = it) ?: it.message)
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { block ->

    block.invokeOnCancellation {
        cancel()
    }

    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            block.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            block.resume(response)
        }
    })

}

fun ResponseBody.text(
    encode: String? = null,
    response: Response? = null
): String {
    return unCompress(response = response) {
        val responseBytes = Utf8BomUtils.removeUTF8BOM(it.readBytes())
        var charsetName: String? = encode

        charsetName?.let {
            return@unCompress String(responseBytes, Charset.forName(charsetName))
        }

        //根据http头判断
        contentType()?.charset()?.let { charset ->
            return@unCompress String(responseBytes, charset)
        }

        //根据内容判断
        charsetName = EncodingDetect.getHtmlEncode(responseBytes)
        return@unCompress String(responseBytes, Charset.forName(charsetName))
    }
}

fun <T> ResponseBody.unCompress(success: (InputStream) -> T): T {
    return unCompress(response = null, success = success)
}

fun <T> ResponseBody.unCompress(
    response: Response? = null,
    success: (InputStream) -> T
): T {
    if (contentType() == "application/zip".toMediaType()) {
        return byteStream().use { byteStream ->
            ZipInputStream(byteStream).use {
                it.nextEntry ?: throw IOException("Empty ZIP archive")
                success(it)
            }
        }
    }

    var input: InputStream = byteStream()
    val encodings = response?.headers?.get("Content-Encoding").orEmpty()
        .lowercase().split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .reversed()
    for (enc in encodings) {
        try {
            input = when (enc) {
                "gzip" -> GZIPInputStream(input)
                "deflate" -> InflaterInputStream(input)
                "br" -> BrotliInputStream(input)
                "zstd" -> ZstdInputStream(input)
                else -> input
            }
        } catch (e: IOException) {
            throw IOException("Decompress ($enc) failed", e)
        }
    }
    return input.use(success)
}

fun Request.Builder.addHeaders(headers: Map<String, String>) {
    headers.forEach {
        addHeader(it.key, it.value)
    }
}

// ==================== 通用构建方法 ====================

private fun buildFormBody(form: Map<String, String>, encoded: Boolean): FormBody {
    return FormBody.Builder().apply {
        form.forEach { (key, value) ->
            if (encoded) addEncoded(key, value) else add(key, value)
        }
    }.build()
}

private fun buildJsonBody(json: String): RequestBody {
    return json.toRequestBody("application/json; charset=UTF-8".toMediaType())
}

private fun buildMultipartBody(type: String?, form: Map<String, Any>): MultipartBody {
    return MultipartBody.Builder().apply {
        type?.let { setType(it.toMediaType()) }
        form.forEach { (key, value) ->
            when (value) {
                is Map<*, *> -> {
                    val fileName = value["fileName"] as String
                    val file = value["file"]
                    val mediaType = (value["contentType"] as? String)?.toMediaType()
                    val requestBody = when (file) {
                        is File -> file.asRequestBody(mediaType)
                        is ByteArray -> file.toRequestBody(mediaType)
                        is String -> file.toRequestBody(mediaType)
                        else -> GSON.toJson(file).toRequestBody(mediaType)
                    }
                    addFormDataPart(key, fileName, requestBody)
                }
                else -> addFormDataPart(key, value.toString())
            }
        }
    }.build()
}

private fun buildUrlWithQuery(url: String, queryMap: Map<String, String>, encoded: Boolean): okhttp3.HttpUrl {
    return url.toHttpUrl().newBuilder().apply {
        queryMap.forEach { (key, value) ->
            if (encoded) addEncodedQueryParameter(key, value) else addQueryParameter(key, value)
        }
    }.build()
}

// ==================== GET & HEAD ====================

fun Request.Builder.get(url: String, queryMap: Map<String, String>, encoded: Boolean = false) {
    url(buildUrlWithQuery(url, queryMap, encoded))
    get()
}

fun Request.Builder.head(url: String, queryMap: Map<String, String> = emptyMap(), encoded: Boolean = false) {
    url(buildUrlWithQuery(url, queryMap, encoded))
    head()
}

// ==================== POST ====================

fun Request.Builder.postForm(form: Map<String, String>, encoded: Boolean = false) {
    post(buildFormBody(form, encoded))
}

fun Request.Builder.postMultipart(type: String?, form: Map<String, Any>) {
    post(buildMultipartBody(type, form))
}

fun Request.Builder.postJson(json: String?) {
    json?.let { post(buildJsonBody(it)) }
}

// ==================== PUT ====================

fun Request.Builder.putForm(form: Map<String, String>, encoded: Boolean = false) {
    put(buildFormBody(form, encoded))
}

fun Request.Builder.putMultipart(type: String?, form: Map<String, Any>) {
    put(buildMultipartBody(type, form))
}

fun Request.Builder.putJson(json: String?) {
    json?.let { put(buildJsonBody(it)) }
}

// ==================== PATCH ====================

fun Request.Builder.patchForm(form: Map<String, String>, encoded: Boolean = false) {
    patch(buildFormBody(form, encoded))
}

fun Request.Builder.patchMultipart(type: String?, form: Map<String, Any>) {
    patch(buildMultipartBody(type, form))
}

fun Request.Builder.patchJson(json: String?) {
    json?.let { patch(buildJsonBody(it)) }
}

// ==================== DELETE ====================

fun Request.Builder.delete(url: String, queryMap: Map<String, String> = emptyMap(), encoded: Boolean = false) {
    url(buildUrlWithQuery(url, queryMap, encoded))
    delete()
}

fun Request.Builder.deleteForm(form: Map<String, String>, encoded: Boolean = false) {
    delete(buildFormBody(form, encoded))
}

fun Request.Builder.deleteJson(json: String?) {
    json?.let { delete(buildJsonBody(it)) }
}

fun Request.Builder.deleteMultipart(type: String?, form: Map<String, Any>) {
    delete(buildMultipartBody(type, form))
}