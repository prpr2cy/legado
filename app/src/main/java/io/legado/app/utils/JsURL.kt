package io.legado.app.utils

import androidx.annotation.Keep
import java.net.URI

@Keep
@Suppress("MemberVisibilityCanBePrivate")
class JsURL(url: String, baseUrl: String? = null) {

    val host: String
    val origin: String
    val path: String?
    val rawPath: String?
    val query: String?
    val rawQuery: String?
    val params: Map<String, String>?

    init {
        val mUrl = if (!baseUrl.isNullOrEmpty()) {
            URI(baseUrl).resolve(url)
        } else {
            URI(url)
        }

        host = mUrl.host

        origin = if (mUrl.port > 0) {
            "${mUrl.scheme}://$host:${mUrl.port}"
        } else {
            "${mUrl.scheme}://$host"
        }

        path = mUrl.path

        rawPath = mUrl.rawPath

        query = mUrl.query

        rawQuery = mUrl.rawQuery

        params = query?.let { _ ->
            hashMapOf<String, String>().apply {
                query.split("&").forEach {
                    val idx = it.indexOf("=")
                    when {
                        idx > 0 -> put(it.substring(0, idx), it.substring(idx + 1))
                        idx == 0 -> put("", it.substring(1)) // 以 = 开头的情况
                        it.isNotEmpty() -> put(it, "") // 有 key 无 value 的情况
                    }
                }
            }
        }
    }
}