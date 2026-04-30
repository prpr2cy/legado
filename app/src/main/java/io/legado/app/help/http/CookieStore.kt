@file:Suppress("unused")

package io.legado.app.help.http

import android.text.TextUtils
import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.equalsRegex
import io.legado.app.constant.AppPattern.semicolonRegex
import io.legado.app.data.appDb
import io.legado.app.data.entities.Cookie
import io.legado.app.help.CacheManager
import io.legado.app.help.http.CookieManager.getCookieNoSession
import io.legado.app.help.http.CookieManager.mergeCookiesToMap
import io.legado.app.help.http.api.CookieManagerInterface
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.removeCookie
import io.legado.app.utils.splitNotBlank

@Keep
object CookieStore : CookieManagerInterface {

    private val webCookieManager: android.webkit.CookieManager by lazy {
        android.webkit.CookieManager.getInstance()
    }

    /**
     *保存cookie到数据库，会自动识别url的二级域名
     */
    override fun setCookie(url: String, cookie: String?) {
        try {
            val domain = NetworkUtils.getSubDomain(url)
            CacheManager.putMemory("${domain}_cookie", cookie ?: "")
            val cookieBean = Cookie(domain, cookie ?: "")
            appDb.cookieDao.insert(cookieBean)
        } catch (e: Exception) {
            AppLog.put("保存Cookie失败\n$e", e)
        }
    }

    fun setWebCookie(url: String, cookie: String) {
        try {
            val baseUrl = NetworkUtils.getBaseUrl(url) ?: return
            val cookies = cookie.splitNotBlank(";")
            webCookieManager.removeSessionCookies(null)
            cookies.forEach {
                webCookieManager.setCookie(baseUrl, it)
            }
            webCookieManager.flush()
        } catch (e: Exception) {
            AppLog.put("设置WebCookie失败\n$e", e)
        }
    }

    override fun replaceCookie(url: String, cookie: String) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(cookie)) {
            return
        }
        val oldCookie = getCookieNoSession(url)
        if (TextUtils.isEmpty(oldCookie)) {
            setCookie(url, cookie)
        } else {
            val cookieMap = cookieToMap(oldCookie)
            cookieMap.putAll(cookieToMap(cookie))
            val newCookie = mapToCookie(cookieMap)
            setCookie(url, newCookie)
        }
    }

    /**
     *获取url所属的二级域名的cookie
     */
    override fun getCookie(url: String): String {
        val domain = NetworkUtils.getSubDomain(url)
        val cookie = getCookieNoSession(url)
        val sessionCookie = CookieManager.getSessionCookie(domain)
        val cookieMap = mergeCookiesToMap(cookie, sessionCookie)
        var cookieString = mapToCookie(cookieMap) ?: ""
        while (cookieString.length > 4096) {
            val removeKey = cookieMap.keys.random()
            CookieManager.removeCookie(url, removeKey)
            cookieMap.remove(removeKey)
            cookieString = mapToCookie(cookieMap) ?: ""
        }
        return cookieString
    }

    fun getKey(url: String, key: String): String {
        val cookie = getCookie(url)
        val sessionCookie = CookieManager.getSessionCookie(url)
        val cookieMap = mergeCookiesToMap(cookie, sessionCookie)
        return cookieMap[key] ?: ""
    }

    fun getWebCookie(url: String): String {
        return try {
            webCookieManager.getCookie(url) ?: ""
        } catch (e: Exception) {
            AppLog.put("获取WebCookie失败\n$e", e)
            ""
        }
    }

    override fun removeCookie(url: String) {
        val domain = NetworkUtils.getSubDomain(url)
        appDb.cookieDao.delete(domain)
        CacheManager.deleteMemory("${domain}_cookie")
        CacheManager.deleteMemory("${domain}_session_cookie")
        // webCookieManager.removeCookie(domain)
        removeWebCookie(url)
    }

    @JvmOverloads
    fun removeWebCookie(
        url: String,
        path: String? = null,
        domain: String? = null
    ) {
        try {
            val baseUrl = NetworkUtils.getBaseUrl(url) ?: return
            val cookie = getWebCookie(baseUrl)
            if (cookie.isNullOrBlank()) return
            val safePath = when {
                path.isNullOrBlank() -> "/"
                !path.startsWith("/") -> "/$path"
                else -> path
            }
            val valueParts = if (domain == null) {
                val subDomain = NetworkUtils.getSubDomain(url)
                arrayOf(
                    "Path=$safePath",
                    "Path=$safePath; Secure",
                    "Path=$safePath; Partitioned",
                    "Path=$safePath; Secure; Partitioned",
                    "Path=$safePath; Domain=$subDomain",
                    "Path=$safePath; Domain=$subDomain; Secure",
                    "Path=$safePath; Domain=$subDomain; Partitioned",
                    "Path=$safePath; Domain=$subDomain; Secure; Partitioned",
                    "Path=$safePath; Domain=.$subDomain",
                    "Path=$safePath; Domain=.$subDomain; Secure",
                    "Path=$safePath; Domain=.$subDomain; Partitioned",
                    "Path=$safePath; Domain=.$subDomain; Secure; Partitioned"
                )
            } else {
                arrayOf(
                    "Path=$safePath",
                    "Path=$safePath; Secure",
                    "Path=$safePath; Partitioned",
                    "Path=$safePath; Secure; Partitioned",
                    "Path=$safePath; Domain=$domain",
                    "Path=$safePath; Domain=$domain; Secure",
                    "Path=$safePath; Domain=$domain; Partitioned",
                    "Path=$safePath; Domain=$domain; Secure; Partitioned"
                )
            }
            cookie.splitNotBlank(";").forEach {
                val name = it.substringBefore("=")
                if (!name.isNullOrBlank()) {
                    valueParts.forEach { part ->
                        webCookieManager.setCookie(baseUrl, "$name=; Max-Age=0; $part")
                    }
                }
            }
            webCookieManager.flush()
        } catch (e: Exception) {
            AppLog.put("删除WebCookie失败\n$e", e)
        }
    }

    override fun cookieToMap(cookie: String?): MutableMap<String, String> {
        if (cookie.isNullOrBlank()) {
            return mutableMapOf()
        }
        return buildMap {
            cookie.splitToSequence(';').forEach {
                val idx = it.indexOf('=')
                if (idx != -1) {
                    val key = it.substring(0, idx).trim { it <= ' ' }
                    val value = it.substring(idx + 1)
                    if (value.isNotBlank() || value.trim { it <= ' ' } == "null") {
                        put(key, value.trim { it <= ' ' })
                    }
                }
            }
        }.toMutableMap()
    }

    override fun mapToCookie(cookieMap: Map<String, String>?): String? {
        if (cookieMap.isNullOrEmpty()) {
            return null
        }
        val builder = StringBuilder()
        cookieMap.keys.forEachIndexed { index, key ->
            if (index > 0) builder.append("; ")
            builder.append(key).append("=").append(cookieMap[key])
        }
        return builder.toString()
    }

    fun clear() {
        appDb.cookieDao.deleteOkHttp()
    }

}