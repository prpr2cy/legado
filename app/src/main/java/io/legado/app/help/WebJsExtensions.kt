package io.legado.app.help

import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cn.hutool.core.codec.Base64
import cn.hutool.core.util.HexUtil
import cn.hutool.crypto.digest.DigestUtil
import cn.hutool.crypto.digest.HMac
import cn.hutool.crypto.symmetric.SymmetricCrypto
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.crypto.AsymmetricCrypto
import io.legado.app.help.CacheManager
import io.legado.app.help.crypto.Sign
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.SSLHelper
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadBook
import io.legado.app.utils.externalCache
import io.legado.app.utils.FileUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.GSON
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.UrlUtil
import java.lang.ref.WeakReference
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jsoup.Connection
import org.jsoup.Jsoup
import splitties.init.appCtx

@Keep
@Suppress("unused")
class WebJsExtensions(
    source: BaseSource,
    activity: AppCompatActivity?,
    webView: WebView
) {
    private val sourceRef: WeakReference<BaseSource?> = WeakReference(source)
    private val activityRef: WeakReference<AppCompatActivity> = WeakReference(activity)
    private val webViewRef: WeakReference<WebView?> = WeakReference(webView)

    private val analyzeRule by lazy {
        AnalyzeRule(source = getSource())
    }

    fun getSource(): BaseSource? {
        return sourceRef.get()
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
                mapOf(
                    "code" to response.code(),
                    "message" to response.message(),
                    "url" to response.url().toString(),
                    "body" to response.body(),
                    "headers" to response.headers().toMultimap(),
                    "cookies" to response.headers().values("set-cookie")
                ).let { GSON.toJson(it) }
            }.onFailure {
                AppLog.put("connect($urlStr) error\n${it.localizedMessage}", it)
            }.getOrElse {
                it.stackTraceToString()
            }
        }
    }

    @JavascriptInterface
    fun fetch(url: String, option: String): String {
        return kotlin.runCatching {
            val options = GSON.fromJsonObject<Map<String, Any>>(option).getOrNull()
                ?: emptyMap<String, Any>()
            val body = options["body"]?.let { value ->
                when (value) {
                    is String -> value
                    is Number, is Boolean -> value.toString()
                    else -> GSON.toJson(value)
                }
            }
            val method = options["method"]?.toString()?.uppercase()
                ?: if (body != null) "POST" else "GET"
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
            if (method != "GET" && method != "HEAD") {
                connect.requestBody(body ?: "")
            }
            connect.method(Connection.Method.valueOf(method))
            val response = connect.execute()
            mapOf(
                "code" to response.statusCode(),
                "message" to response.statusMessage(),
                "url" to response.url().toString(),
                "body" to response.body(),
                "headers" to response.headers(),
                "cookies" to response.cookies()
            ).let { GSON.toJson(it) }
        }.onFailure {
            AppLog.put("fetch(${url}) error\n${it.localizedMessage}", it)
        }.getOrElse {
            it.stackTraceToString()
        }
    }

    private fun getFile(path: String): File {
        val cachePath = appCtx.externalCache.absolutePath
        return when {
            path.startsWith("/storage") -> File(path)
            path.startsWith(File.separator) -> File(cachePath + path)
            else -> File(cachePath + File.separator + path)
        }
    }

    @JavascriptInterface
    fun readTxtFile(path: String, charsetName: String): String {
        val file = getFile(path)
        return if (file.exists()) {
            file.readText(Charset.forName(charsetName))
        } else {
            ""
        }
    }

    @JavascriptInterface
    fun deleteFile(path: String): Boolean {
        val file = getFile(path)
        return FileUtils.delete(file, true)
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

    private fun symmetricCrypto(
        algorithm: String,
        key: String,
        iv: String?
    ): SymmetricCrypto {
        return SymmetricCrypto(algorithm, key.encodeToByteArray()).apply {
            if (!iv.isNullOrEmpty()) {
                setIv(iv.encodeToByteArray())
            }
        }
    }

    @JavascriptInterface
    fun decryptStr(data: String, algorithm: String, key: String, iv: String?): String {
        return symmetricCrypto(algorithm, key, iv).decryptStr(data)
    }

    @JavascriptInterface
    fun encryptHex(data: String, algorithm: String, key: String, iv: String?): String {
        return symmetricCrypto(algorithm, key, iv).encryptHex(data)
    }

    @JavascriptInterface
    fun encryptBase64(data: String, algorithm: String, key: String, iv: String?): String {
        return symmetricCrypto(algorithm, key, iv).encryptBase64(data)
    }

    private fun asymmetricCrypto(
        algorithm: String,
        key: String,
        usePublicKey: Boolean
    ): AsymmetricCrypto {
        return AsymmetricCrypto(algorithm).apply {
            val keyBytes = Base64.decode(key)
            if (usePublicKey) {
                setPublicKey(keyBytes)
            } else {
                setPrivateKey(keyBytes)
            }
        }
    }

    @JavascriptInterface
    fun decryptWithPublicKey(data: String, algorithm: String, key: String): String {
        return asymmetricCrypto(algorithm, key, true).decryptStr(data, true)
    }

    @JavascriptInterface
    fun encryptWithPublicKey(data: String, algorithm: String, key: String): String {
        return asymmetricCrypto(algorithm, key, true).encryptBase64(data, true)
    }

    @JavascriptInterface
    fun decryptWithPrivateKey(data: String, algorithm: String, key: String): String {
        return asymmetricCrypto(algorithm, key, false).decryptStr(data, false)
    }

    @JavascriptInterface
    fun encryptWithPrivateKey(data: String, algorithm: String, key: String): String {
        return asymmetricCrypto(algorithm, key, false).encryptBase64(data, false)
    }

    @JavascriptInterface
    fun createSignHex(
        data: String,
        algorithm: String,
        publicKey: String?,
        privateKey: String?
    ): String {
        return Sign(algorithm).apply {
            if (!publicKey.isNullOrEmpty()) {
                setPublicKey(Base64.decode(publicKey))
            }
            if (!privateKey.isNullOrEmpty()) {
                setPrivateKey(Base64.decode(privateKey))
            }
        }.signHex(data)
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

    @JavascriptInterface
    fun request(funName: String, jsParam: Array<String?>, id: String) {
        val activity = activityRef.get() ?: return
        Coroutine.async(activity.lifecycleScope) {
            val p0 = jsParam.getOrNull(0)
            val p1 = jsParam.getOrNull(1)
            val p2 = jsParam.getOrNull(2)
            val p3 = jsParam.getOrNull(3)

            when (funName) {
                "runAwait" -> {
                    val jsCode = p0 ?: throw NoStackTraceException("error null")
                    analyzeRule.evalJS(jsCode)?.let { result ->
                        when (result) {
                            is String -> result
                            is ByteArray -> Base64.encode(result)
                            is IntArray -> GSON.toJson(result)
                            is LongArray -> GSON.toJson(result)
                            is DoubleArray -> GSON.toJson(result)
                            is ShortArray -> GSON.toJson(result)
                            is CharArray -> GSON.toJson(result)
                            is BooleanArray -> GSON.toJson(result)
                            is Array<*> -> GSON.toJson(result)
                            is List<*> -> GSON.toJson(result)
                            is Map<*, *> -> GSON.toJson(result)
                            else -> result.toString()
                        }
                    }
                }
                "ajaxAwait" -> {
                    val url = p0 ?: throw NoStackTraceException("error url null")
                    ajax(url)
                }
                "connectAwait" -> {
                    val url = p0 ?: throw NoStackTraceException("error url null")
                    connect(url)
                }
                "fetchAwait" -> {
                    val url = p0 ?: throw NoStackTraceException("error url null")
                    val option = p1 ?: throw NoStackTraceException("error option null")
                    fetch(url, option)
                }
                "readTxtFileAwait" -> {
                    val path = p0 ?: throw NoStackTraceException("error path null")
                    val charset = p1 ?: throw NoStackTraceException("error charset null")
                    readTxtFile(path, charset)
                }
                "decryptStrAwait" -> {
                    val data = p0 ?: throw NoStackTraceException("error data null")
                    val algorithm = p1 ?: throw NoStackTraceException("error algorithm null")
                    val key = p2 ?: throw NoStackTraceException("error key null")
                    val iv = p3
                    symmetricCrypto(algorithm, key, iv).decryptStr(data)
                }
                "encryptHexAwait" -> {
                    val data = p0 ?: throw NoStackTraceException("error data null")
                    val algorithm = p1 ?: throw NoStackTraceException("error algorithm null")
                    val key = p2 ?: throw NoStackTraceException("error key null")
                    val iv = p3
                    symmetricCrypto(algorithm, key, iv).encryptHex(data)
                }
                "encryptBase64Await" -> {
                    val data = p0 ?: throw NoStackTraceException("error data null")
                    val algorithm = p1 ?: throw NoStackTraceException("error algorithm null")
                    val key = p2 ?: throw NoStackTraceException("error key null")
                    val iv = p3
                    symmetricCrypto(algorithm, key, iv).encryptBase64(data)
                }
                "decryptWithPublicKeyAwait" -> {
                    val data = p0 ?: throw NoStackTraceException("error data null")
                    val algorithm = p1 ?: throw NoStackTraceException("error algorithm null")
                    val publicKey = p2 ?: throw NoStackTraceException("error publicKey null")
                    asymmetricCrypto(algorithm, publicKey, true).decryptStr(data)
                }
                "encryptWithPublicKeyAwait" -> {
                    val data = p0 ?: throw NoStackTraceException("error data null")
                    val algorithm = p1 ?: throw NoStackTraceException("error algorithm null")
                    val publicKey = p2 ?: throw NoStackTraceException("error publicKey null")
                    asymmetricCrypto(algorithm, publicKey, true).encryptBase64(data)
                }
                "decryptWithPrivateKeyAwait" -> {
                    val data = p0 ?: throw NoStackTraceException("error data null")
                    val algorithm = p1 ?: throw NoStackTraceException("error algorithm null")
                    val privateKey = p2 ?: throw NoStackTraceException("error privateKey null")
                    asymmetricCrypto(algorithm, privateKey, false).decryptStr(data)
                }
                "encryptWithPrivateKeyAwait" -> {
                    val data = p0 ?: throw NoStackTraceException("error data null")
                    val algorithm = p1 ?: throw NoStackTraceException("error algorithm null")
                    val privateKey = p2 ?: throw NoStackTraceException("error privateKey null")
                    asymmetricCrypto(algorithm, privateKey, false).encryptBase64(data)
                }
                "createSignHexAwait" -> {
                    val data = p0 ?: throw NoStackTraceException("error data null")
                    val algorithm = p1 ?: throw NoStackTraceException("error algorithm null")
                    val publicKey = p2
                    val privateKey = p3
                    createSignHex(data, algorithm, publicKey, privateKey)
                }
                else -> throw NoStackTraceException("error funName: $funName")
            }
        }.onSuccess { result ->
            CacheManager.putMemory(id, result ?: "")
            webViewRef.get()?.evaluateJavascript("window.$JSBridgeResult('$id', true);", null)
        }.onError {
            val errorMessage = it.localizedMessage ?: "Unknown error"
            CacheManager.putMemory(id, errorMessage)
            webViewRef.get()?.evaluateJavascript("window.$JSBridgeResult('$id', false);", null)
        }
    }

    companion object {
        private fun getRandomLetter(): Char {
            val letters = "abcdefghijklmnopqrstuvwxyz"
            return letters.random()
        }

        private val uuid by lazy {
            UUID.randomUUID().toString().replace('-', getRandomLetter()).chunked(6)
        }

        private val uuid2 by lazy {
            UUID.randomUUID().toString().replace('-', getRandomLetter()).chunked(6)
        }

        val nameJava by lazy { getRandomLetter() + uuid[0] + uuid2[0] }
        val nameCache by lazy { getRandomLetter() + uuid[1] + uuid2[1] }
        val JSBridgeResult by lazy { getRandomLetter() + uuid[2] + uuid2[2] }

        val JS_INJECTION by lazy { """
            const requestId = n => 'req_' + n + '_' + Date.now() + '_' + Math.random().toString(36).slice(-3);
            const JSBridgeCallbacks = {};
            const java = window.$nameJava;
            delete window.$nameJava;
            const cache = window.$nameCache;
            delete window.$nameCache;
            function runAwait(jsCode) {
                return new Promise((resolve, reject) => {
                    const id = requestId('runAwait');
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request('runAwait', [String(jsCode)], id);
                });
            };
            function ajaxAwait(url) {
                return new Promise((resolve, reject) => {
                    const id = requestId('ajaxAwait');
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request('ajaxAwait', [url], id);
                });
            };
            function connectAwait(url) {
                return new Promise((resolve, reject) => {
                    const id = requestId('connectAwait');
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request('connectAwait', [url], id);
                });
            };
            function fetchAwait(url, options) {
                const optionStr = options ? JSON.stringify(options) : '{}';
                return new Promise((resolve, reject) => {
                    const id = requestId('fetchAwait');
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request('fetchAwait', [url, optionStr], id);
                });
            };
            function readTxtFileAwait(path, charset) {
                return new Promise((resolve, reject) => {
                    const id = requestId('readTxtFileAwait');
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request('readTxtFileAwait', [path, charset || 'UTF-8'], id);
                });
            };
            function decryptStrAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId('decryptStrAwait');
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request('decryptStrAwait', params(args), id);
                });
            };
            function encryptHexAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId('encryptHexAwait');
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request('encryptHexAwait', params(args), id);
                });
            };
            function encryptBase64Await(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId('encryptBase64Await');
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request('encryptBase64Await', params(args), id);
                });
            };
            function decryptWithPublicKeyAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId('decryptWithPublicKeyAwait');
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request('decryptWithPublicKeyAwait', params(args), id);
                });
            };
            function encryptWithPublicKeyAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId('encryptWithPublicKeyAwait');
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request('encryptWithPublicKeyAwait', params(args), id);
                });
            };
            function decryptWithPrivateKeyAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId('decryptWithPrivateKeyAwait');
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request('decryptWithPrivateKeyAwait', params(args), id);
                });
            };
            function encryptWithPrivateKeyAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId('encryptWithPrivateKeyAwait');
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request('encryptWithPrivateKeyAwait', params(args), id);
                });
            };
            function createSignHexAwait(...args) {
                return new Promise((resolve, reject) => {
                    const id = requestId('createSignHexAwait');
                    JSBridgeCallbacks[id] = { resolve, reject };
                    java.request('createSignHexAwait', params(args), id);
                });
            };
            window.$JSBridgeResult = function(id, success) {
                const callBack = JSBridgeCallbacks[id];
                if (callBack) {
                    const result = cache.getFromMemory(id);
                    if (success) {
                        callBack.resolve(result);
                    } else {
                        callBack.reject(result);
                    }
                    delete JSBridgeCallbacks[id];
                }
            };""".trimIndent()
        }
    }

}
