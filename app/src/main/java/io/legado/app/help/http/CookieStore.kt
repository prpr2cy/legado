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
        removeWebCookie(url)
    }

    fun removeWebCookie(url: String) {
        val baseUrl = NetworkUtils.getBaseUrl(url) ?: return
        removeWebCookie(baseUrl, null, null)
    }

    fun removeWebCookie(url: String, autoDomain: Boolean) {
        val baseUrl = NetworkUtils.getBaseUrl(url) ?: return
        val domain = if (autoDomain) {
            NetworkUtils.getSubDomainOrNull(url)
        } else null
        removeWebCookie(baseUrl, null, domain)
    }

    fun removeWebCookie(
        url: String,
        path: String? = null,
        domain: String? = null
    ) {
        try {
            if (url.isNullOrBlank()) return
            val cookie = getWebCookie(url)
            if (cookie.isNullOrBlank()) return
            val safePath = when {
                path.isNullOrBlank() -> "/"
                !path.startsWith("/") -> "/$path"
                else -> path
            }
            cookie.splitNotBlank(";").forEach {
                val name = it.substringBefore("=")
                if (!name.isNullOrBlank()) {
                    val cookieValue = buildString {
                        append("$name=; Max-Age=0; Path=$safePath")
                        if (!domain.isNullOrBlank()) append("; Domain=$domain")
                    }
                    webCookieManager.setCookie(url, cookieValue)
                }
            }
            webCookieManager.flush()
        } catch (e: Exception) {
            AppLog.put("删除WebCookie失败\n$e", e)
        }
    }

    override fun cookieToMap(cookie: String?): MutableMap<String, String> {
        val cookieMap = mutableMapOf<String, String>()
        if (cookie.isNullOrBlank()) {
            return cookieMap
        }
        val pairArray = cookie.split(semicolonRegex).dropLastWhile { it.isEmpty() }.toTypedArray()
        for (pair in pairArray) {
            val pairs = pair.split(equalsRegex, 2).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (pairs.size <= 1) {
                continue
            }
            val key = pairs[0].trim { it <= ' ' }
            val value = pairs[1]
            if (value.isNotBlank() || value.trim { it <= ' ' } == "null") {
                cookieMap[key] = value.trim { it <= ' ' }
            }
        }
        return cookieMap
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