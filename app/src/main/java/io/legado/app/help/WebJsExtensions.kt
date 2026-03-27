package io.legado.app.help

import android.webkit.JavascriptInterface
import androidx.annotation.Keep
import cn.hutool.core.codec.Base64
import cn.hutool.core.util.HexUtil
import cn.hutool.crypto.digest.DigestUtil
import cn.hutool.crypto.digest.HMac
import cn.hutool.crypto.symmetric.SymmetricCrypto
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.crypto.AsymmetricCrypto
import io.legado.app.help.crypto.Sign
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.SSLHelper
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.GSON
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.toastOnUi
import java.lang.ref.WeakReference
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jsoup.Connection
import org.jsoup.Jsoup
import splitties.init.appCtx

@Keep
@Suppress("unused")
class WebJsExtensions(source: BaseSource?) {
    val sourceRef: WeakReference<BaseSource?> = WeakReference(source)

    fun getSource(): BaseSource? {
        return sourceRef.get()
    }

    fun createSymmetricCrypto(
        algorithm: String,
        key: ByteArray?,
        iv: ByteArray?
    ): SymmetricCrypto {
        val symmetricCrypto = SymmetricCrypto(algorithm, key)
        return if (iv != null) symmetricCrypto.setIv(iv) else symmetricCrypto
    }

    fun createAsymmetricCrypto(
        algorithm: String
    ): AsymmetricCrypto {
        return AsymmetricCrypto(algorithm)
    }

    @JavascriptInterface
    fun put(key: String, value: String): String {
        getSource()?.put(key, value)
        return value
    }

    @JavascriptInterface
    fun get(key: String): String {
        return getSource()?.get(key) ?: ""
    }

    @JavascriptInterface
    fun toast(msg: String) {
        appCtx.toastOnUi("${getSource()?.getTag()}: $msg")
    }

    @JavascriptInterface
    fun longToast(msg: String) {
        appCtx.longToastOnUi("${getSource()?.getTag()}: $msg")
    }

    @JavascriptInterface
    fun log(msg: String): String {
        AppLog.putDebug("${getSource()?.getTag()}: $msg")
        return msg
    }

    @JavascriptInterface
    fun ajax(urlStr: String): String {
        return runBlocking {
            kotlin.runCatching {
                val analyzeUrl = AnalyzeUrl(urlStr, source = getSource())
                analyzeUrl.getStrResponseAwait().body ?: ""
            }.onFailure {
                AppLog.put("ajax(${urlStr}) error\n${it.localizedMessage}", it)
            }.getOrElse {
                it.stackTraceToString()
            }
        }
    }

    @JavascriptInterface
    fun connect(urlStr: String): String {
        return runBlocking {
            kotlin.runCatching {
                val analyzeUrl = AnalyzeUrl(urlStr, source = getSource())
                val response = analyzeUrl.getStrResponseAwait()
                val result = mapOf(
                    "code" to response.code(),
                    "message" to response.message(),
                    "url" to response.url().toString(),
                    "body" to response.body(),
                    "headers" to response.headers().toMultimap(),
                    "cookies" to response.headers().values("set-cookie")
                )
                GSON.toJson(result)
            }.onFailure {
                AppLog.put("connect($urlStr) error\n${it.localizedMessage}", it)
            }.getOrElse {
                it.stackTraceToString()
            }
        }
    }

    @JavascriptInterface
    fun fetch(url: String, option: String): String {
        return runBlocking {
            kotlin.runCatching {
                val options = GSON.fromJsonObject<Map<String, Any>>(option).getOrNull()
                    ?: emptyMap<String, Any>()
                var method = options["method"]?.toString()?.uppercase()
                val body = options["body"]?.let { value ->
                    when (value) {
                        is String -> value
                        is Number, is Boolean -> value.toString()
                        else -> GSON.toJson(value)
                    }
                }
                if (method == null) {
                    method = if (body != null) "POST" else "GET"
                }
                val headers = (options["headers"] as? Map<*, *>)
                    ?.mapKeys { entry -> entry.key.toString() }
                    ?.mapValues { entry -> entry.value?.toString() ?: "" }
                    ?: emptyMap<String, String>()
                val timeout = (options["timeout"] as? Number)?.toInt() ?: 30000
                val connect = Jsoup.connect(url)
                    .sslSocketFactory(SSLHelper.unsafeSSLSocketFactory)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .followRedirects(false)
                    .maxBodySize(0)
                    .timeout(timeout)
                    .headers(headers)
                    .method(Connection.Method.valueOf(method))
                if (body != null && method != "GET" && method != "HEAD") {
                    connect.requestBody(body)
                }
                val response = connect.execute()
                val result = mapOf(
                    "code" to response.statusCode(),
                    "message" to response.statusMessage(),
                    "url" to response.url().toString(),
                    "body" to response.body(),
                    "headers" to response.headers(),
                    "cookies" to response.cookies()
                )
                GSON.toJson(result)
            }.onFailure {
                AppLog.put("fetch(${url}) error\n${it.localizedMessage}", it)
            }.getOrElse {
                it.stackTraceToString()
            }
        }
    }

    @JavascriptInterface
    fun importScript(path: String): String {
        return JsExtensions.importScript(path)
    }

    @JavascriptInterface
    fun strToBytes(str: String, charset: String): ByteArray {
        return str.toByteArray(charset(charset))
    }

    @JavascriptInterface
    fun bytesToStr(bytes: ByteArray, charset: String): String {
        return String(bytes, charset(charset))
    }

    @JavascriptInterface
    fun base64Decode(str: String): String {
        return Base64.decodeStr(str)
    }

    @JavascriptInterface
    fun base64Encode(str: String): String {
        return Base64.encode(str)
    }

    @JavascriptInterface
    fun hexDecodeToString(hex: String): String {
        return HexUtil.decodeHexStr(hex)
    }

    @JavascriptInterface
    fun hexEncodeToString(str: String): String {
        return HexUtil.encodeHexStr(str)
    }

    @JavascriptInterface
    fun md5Encode(str: String): String {
        return DigestUtil.digester("MD5").digestHex(str)
    }

    @JavascriptInterface
    fun md5Encode16(str: String): String {
        return DigestUtil.digester("MD5").digestHex(str).substring(8, 24)
    }

    @JavascriptInterface
    fun digestHex(data: String, algorithm: String): String {
        return DigestUtil.digester(algorithm).digestHex(data)
    }

    @JavascriptInterface
    fun digestBase64Str(data: String, algorithm: String): String {
        return Base64.encode(DigestUtil.digester(algorithm).digest(data))
    }

    @JavascriptInterface
    fun HMacHex(data: String, algorithm: String, key: String): String {
        return HMac(algorithm, key.toByteArray()).digestHex(data)
    }

    @JavascriptInterface
    fun HMacBase64(data: String, algorithm: String, key: String): String {
        return Base64.encode(HMac(algorithm, key.toByteArray()).digest(data))
    }

    @JavascriptInterface
    fun decryptStr(data: String, algorithm: String, key: String, iv: String): String {
        return createSymmetricCrypto(
            algorithm,
            key.encodeToByteArray(),
            if (iv.isEmpty()) null else iv.encodeToByteArray()
        ).decryptStr(data)
    }

    @JavascriptInterface
    fun encryptHex(data: String, algorithm: String, key: String, iv: String): String {
        return createSymmetricCrypto(
            algorithm,
            key.encodeToByteArray(),
            if (iv.isEmpty()) null else iv.encodeToByteArray()
        ).encryptHex(data)
    }

    @JavascriptInterface
    fun encryptBase64(data: String, algorithm: String, key: String, iv: String): String {
        return createSymmetricCrypto(
            algorithm,
            key.encodeToByteArray(),
            if (iv.isEmpty()) null else iv.encodeToByteArray()
        ).encryptBase64(data)
    }

    @JavascriptInterface
    fun decryptWithPublicKey(data: String, algorithm: String, key: String): String {
        return createAsymmetricCrypto(algorithm)
            .setPublicKey(key)
            .decryptStr(data, true)
    }

    @JavascriptInterface
    fun encryptWithPublicKey(data: String, algorithm: String, key: String): String {
        return createAsymmetricCrypto(algorithm)
            .setPublicKey(key)
            .encryptBase64(data, true)
    }

    @JavascriptInterface
    fun decryptWithPrivateKey(data: String, algorithm: String, key: String): String {
        return createAsymmetricCrypto(algorithm)
            .setPrivateKey(key)
            .decryptStr(data, false)
    }

    @JavascriptInterface
    fun encryptWithPrivateKey(data: String, algorithm: String, key: String): String {
        return createAsymmetricCrypto(algorithm)
            .setPrivateKey(key)
            .encryptBase64(data, false)
    }

    @JavascriptInterface
    fun createSignWithPublicKey(data: String, algorithm: String, key: String): String {
        return Sign(algorithm).setPublicKey(key).signHex(data)
    }

    @JavascriptInterface
    fun createSignWithPrivateKey(data: String, algorithm: String, key: String): String {
        return Sign(algorithm).setPrivateKey(key).signHex(data)
    }

    @JavascriptInterface
    fun randomUUID(): String {
        return UUID.randomUUID().toString()
    }

    @JavascriptInterface
    fun androidId(): String {
        return AppConst.androidId
    }

    @JavascriptInterface
    fun getCookie(url: String): String {
        return CookieStore.getCookie(url)
    }

    @JavascriptInterface
    fun getKey(url: String, key: String): String {
        return CookieStore.getKey(url, key)
    }

    @JavascriptInterface
    fun setCookie(url: String, cookie: String) {
        CookieStore.setCookie(url, cookie)
    }

    @JavascriptInterface
    fun replaceCookie(url: String, cookie: String) {
        CookieStore.replaceCookie(url, cookie)
    }

    @JavascriptInterface
    fun removeCookie(url: String) {
        CookieStore.removeCookie(url)
    }

    @JavascriptInterface
    fun getWebCookie(url: String): String {
        return CookieStore.getWebCookie(url)
    }

    @JavascriptInterface
    fun setWebCookie(url: String, cookie: String) {
        CookieStore.setWebCookie(url, cookie)
    }

    @JavascriptInterface
    fun removeWebCookie(url: String) {
        CookieStore.removeWebCookie(url)
    }

}
