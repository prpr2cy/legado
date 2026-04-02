package io.legado.app.ui.browser

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.webkit.URLUtil
import android.webkit.WebView
import androidx.documentfile.provider.DocumentFile
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.ACache
import io.legado.app.utils.DocumentUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.toastOnUi
import io.legado.app.help.WebJsExtensions.Companion.JS_INJECTION
import io.legado.app.utils.writeBytes
import org.apache.commons.text.StringEscapeUtils
import java.io.File
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Date

class WebViewModel(application: Application) : BaseViewModel(application) {
    var intent: Intent? = null
    var baseUrl: String = ""
    var html: String? = null
    var localHtml: Boolean = false
    val headerMap: HashMap<String, String> = hashMapOf()
    var sourceVerificationEnable: Boolean = false
    var refetchAfterSuccess: Boolean = true
    var sourceOrigin: String = ""
    var sourceName: String = ""
    var source: BaseSource? = null

    fun initData(
        intent: Intent,
        success: () -> Unit
    ) {
        execute {
            this@WebViewModel.intent = intent
            val url = intent.getStringExtra("url")
                ?: throw NoStackTraceException("url不能为空")
            sourceOrigin = intent.getStringExtra("sourceOrigin") ?: ""
            sourceName = intent.getStringExtra("sourceName") ?: ""
            sourceVerificationEnable = intent.getBooleanExtra("sourceVerificationEnable", false)
            refetchAfterSuccess = intent.getBooleanExtra("refetchAfterSuccess", true)
            html = intent.getStringExtra("html")
            source = SourceHelp.getSource(sourceOrigin)
            val analyzeUrl = AnalyzeUrl(url, source = source)
            baseUrl = analyzeUrl.url
            headerMap.putAll(analyzeUrl.headerMap)
            if (baseUrl.startsWith("data:text/html", ignoreCase = true) && html.isNullOrBlank()) {
                val dataUri = baseUrl.substringAfter("data:text/html")
                val metaIndex = dataUri.indexOf(",")
                if (metaIndex != -1) {
                    var origin = "data:text/html"
                    val meta = dataUri.substring(0, metaIndex).lowercase()
                    val data = dataUri.substring(metaIndex + 1)
                    val charset = if (meta.contains("charset=")) {
                        val name = meta.substringAfter("charset=")
                            .substringBefore(";")
                            .trim()
                        origin = "${origin};charset=${name}"
                        Charset.forName(name)
                    } else Charsets.UTF_8
                    html = if (meta.contains("base64")) {
                        origin = "${origin};base64"
                        String(Base64.decode(data, Base64.DEFAULT), charset)
                    } else  {
                        URLDecoder.decode(data, charset.name())
                    }
                    baseUrl = "${origin},"
                }
            }
            html?.let {
                val headIndex = it.indexOf("<head", ignoreCase = true)
                html = if (headIndex != -1) {
                    val closingHeadIndex = it.indexOf(">", startIndex = headIndex)
                    if (closingHeadIndex != -1) {
                        val insertPos = closingHeadIndex + 1
                        StringBuilder(it).insert(insertPos, "<script>$JS_INJECTION</script>").toString()
                    } else {
                        "<head><script>$JS_INJECTION</script></head>$it"
                    }
                } else {
                    "<head><script>$JS_INJECTION</script></head>$it"
                }
                localHtml = true
            }
            if (analyzeUrl.isPost()) {
                html = analyzeUrl.getStrResponseAwait(useWebView = false).body
            }
        }.onSuccess {
            success.invoke()
        }.onError {
            context.toastOnUi("error\n${it.localizedMessage}")
            it.printOnDebug()
        }
    }

    fun saveImage(webPic: String?, path: String) {
        webPic ?: return
        execute {
            val fileName = "${AppConst.fileNameFormat.format(Date(System.currentTimeMillis()))}.jpg"
            webData2bitmap(webPic)?.let { byteArray ->
                if (path.isContentScheme()) {
                    val uri = Uri.parse(path)
                    DocumentFile.fromTreeUri(context, uri)?.let { doc ->
                        DocumentUtils.createFileIfNotExist(doc, fileName)
                            ?.writeBytes(context, byteArray)
                    }
                } else {
                    val realPath = path.removePrefix("file://")
                    val file = FileUtils.createFileIfNotExist(File(realPath), fileName)
                    file.writeBytes(byteArray)
                }
            } ?: throw Throwable("NULL")
        }.onError {
            ACache.get().remove(imagePathKey)
            context.toastOnUi("保存图片失败:${it.localizedMessage}")
        }.onSuccess {
            context.toastOnUi("保存成功")
        }
    }

    private suspend fun webData2bitmap(data: String): ByteArray? {
        return if (URLUtil.isValidUrl(data)) {
            okHttpClient.newCallResponseBody {
                url(data)
            }.bytes()
        } else {
            Base64.decode(data.split(",").toTypedArray()[1], Base64.DEFAULT)
        }
    }

    fun saveVerificationResult(webView: WebView, success: () -> Unit) {
        if (!sourceVerificationEnable) {
            return success.invoke()
        }
        if (refetchAfterSuccess) {
            execute {
                val url = intent!!.getStringExtra("url")!!
                html = AnalyzeUrl(
                    url,
                    source = source,
                    headerMapF = headerMap
                ).getStrResponseAwait(useWebView = false).body
            }.onSuccess {
                SourceVerificationHelp.setResult(sourceOrigin, html ?: "", baseUrl)
                success.invoke()
            }.onError {
                throw NoStackTraceException(it.localizedMessage ?: "error")
            }
        } else {
            webView.evaluateJavascript("document.documentElement.outerHTML") {
                execute {
                    html = StringEscapeUtils.unescapeJson(it).trim('"')
                }.onSuccess {
                    SourceVerificationHelp.setResult(sourceOrigin, html ?: "", webView.url ?: "")
                    success.invoke()
                }.onError {
                    throw NoStackTraceException(it.localizedMessage ?: "error")
                }
            }
        }
    }
}