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
            if (baseUrl.startsWith("data:text/html", ignoreCase = true) && html == null) {
                baseUrl = injectJsToDataUri(baseUrl)
                localHtml = true
            }
            html?.let {
                html = injectJs(it)
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

    private fun injectJs(html: String): String {
        val headIndex = html.indexOf("<head", ignoreCase = true)
        return if (headIndex != -1) {
            val closingHeadIndex = html.indexOf(">", startIndex = headIndex)
            if (closingHeadIndex != -1) {
                val insertPos = closingHeadIndex + 1
                StringBuilder(html).insert(insertPos, "<script>$JS_INJECTION</script>").toString()
            } else {
                "<head><script>$JS_INJECTION</script></head>$html"
            }
        } else {
            "<head><script>$JS_INJECTION</script></head>$html"
        }   
    }

    private fun injectJsToDataUri(dataUri: String): String {
        val metaIndex = dataUri.indexOf(",")
        return if (metaIndex != -1) {
            val meta = dataUri.substring(0, metaIndex).lowercase()
            val data = dataUri.substring(metaIndex + 1).trim()
            if (meta.contains("charset=") && !meta.contains("charset=utf-8")) {
                throw NoStackTraceException("不支持非UTF-8编码的dataUri\n${dataUri}")
            }
            if (meta.contains("base64")) {
                val decodeData = String(Base64.decode(data, Base64.NO_WRAP), Charsets.UTF_8)
                val encodeData = Base64.encodeToString(
                    injectJs(decodeData).toByteArray(), Base64.NO_WRAP
                )
                "${meta},${encodeData}"
            } else  {
                val decodeData = Uri.decode(data)
                val encodeData = Uri.encode(injectJs(decodeData), null)
                "${meta},${encodeData}"
            }
        } else {
            throw NoStackTraceException("dataUri格式不正确:\n${dataUri}")
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