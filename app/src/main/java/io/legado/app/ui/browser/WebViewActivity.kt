package io.legado.app.ui.browser

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.core.view.size
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.databinding.ActivityWebViewBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieStore
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.help.WebCacheManager
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_INJECTION
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.webView.WebViewPool.BLANK_HTML
import io.legado.app.help.webView.WebViewPool.DATA_HTML
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.Download
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.ACache
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import java.net.URLDecoder

class WebViewActivity : VMBaseActivity<ActivityWebViewBinding, WebViewModel>() {
    companion object {
        // 是否输出日志
        var sessionShowWebLog = false
    }

    private lateinit var pooledWebView: PooledWebView
    private lateinit var currentWebView: WebView

    override val binding by viewBinding(ActivityWebViewBinding::inflate)
    override val viewModel by viewModels<WebViewModel>()
    private var customWebViewCallback: WebChromeClient.CustomViewCallback? = null
    private var webPic: String? = null
    private var isCloudflareChallenge = false
    private var needClearHistory = true
    private val saveImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ACache.get().put(imagePathKey, uri.toString())
            viewModel.saveImage(webPic, uri.toString())
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        pooledWebView = WebViewPool.acquire(this)
        currentWebView = pooledWebView.realWebView
        binding.webViewContainer.addView(currentWebView)
        currentWebView.post {
            currentWebView.clearHistory()
        }
        binding.titleBar.title = intent.getStringExtra("title") ?: getString(R.string.loading)
        binding.titleBar.subtitle = intent.getStringExtra("sourceName")
        viewModel.initData(intent) {
            val url = viewModel.baseUrl
            val headerMap = viewModel.headerMap
            initWebView(url, headerMap)
            val html = viewModel.html
            if (viewModel.localHtml) {
                viewModel.source?.let {
                    val webJsExtensions = WebJsExtensions(it, this, currentWebView)
                    currentWebView.addJavascriptInterface(webJsExtensions, nameJava)
                }
                currentWebView.addJavascriptInterface(WebCacheManager, nameCache)
            }
            if (html.isNullOrEmpty()) {
                currentWebView.loadUrl(url, headerMap)
            } else {
                currentWebView.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
            }
        }
        currentWebView.clearHistory()
        onBackPressedDispatcher.addCallback(this) {
            if (binding.customWebView.size > 0) {
                customWebViewCallback?.onCustomViewHidden()
                return@addCallback
            }
            // 智能回退逻辑
            if (currentWebView.canGoBack()) {
                val list = currentWebView.copyBackForwardList()
                val size = list.size
                if (size == 1) {
                    finish()
                    return@addCallback
                }
                val currentIndex = list.currentIndex
                val currentItem = list.currentItem
                val currentUrl = currentItem?.originalUrl ?: BLANK_HTML
                val currentTitle = currentItem?.title
                var steps = 1

                for (i in currentIndex - 1 downTo 0) {
                    val item = list.getItemAtIndex(i)
                    val itemUrl = item.originalUrl
                    if (itemUrl == BLANK_HTML) {
                        finish()
                        return@addCallback
                    }
                    if (itemUrl != currentUrl || currentTitle != item.title) {
                        break
                    }
                    if (currentUrl == DATA_HTML) {
                        break
                    }
                    steps++
                }

                if (steps == size) {
                    finish()
                    return@addCallback
                }
                currentWebView.goBackOrForward(-steps)
                return@addCallback
            }
            finish()
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.web_view, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_show_web_log)?.isChecked = sessionShowWebLog
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_web_refresh -> currentWebView.reload()
            R.id.menu_open_in_browser -> openUrl(viewModel.baseUrl)
            R.id.menu_copy_url -> sendToClip(viewModel.baseUrl)
            R.id.menu_ok -> {
                if (viewModel.sourceVerificationEnable) {
                    viewModel.saveVerificationResult(currentWebView) {
                        finish()
                    }
                } else {
                    finish()
                }
            }
            R.id.menu_show_web_log -> {
                sessionShowWebLog = !sessionShowWebLog
                item.isChecked = sessionShowWebLog
            }
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(url: String, headerMap: HashMap<String, String>) {
        binding.progressBar.fontColor = accentColor
        currentWebView.webChromeClient = CustomWebChromeClient()
        currentWebView.webViewClient = CustomWebViewClient()
        currentWebView.settings.apply {
            useWideViewPort = true
            loadWithOverviewMode = true
            headerMap[AppConst.UA_NAME]?.let {
                userAgentString = it
            }
        }
        CookieStore.setWebCookie(url, CookieStore.getCookie(url))
        currentWebView.setOnLongClickListener {
            val hitTestResult = currentWebView.hitTestResult
            if (hitTestResult.type == WebView.HitTestResult.IMAGE_TYPE ||
                hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                hitTestResult.extra?.let {
                    saveImage(it)
                    return@setOnLongClickListener true
                }
            }
            return@setOnLongClickListener false
        }
        currentWebView.setDownloadListener { downloadUrl, _, contentDisposition, _, _ ->
            var fileName = URLUtil.guessFileName(downloadUrl, contentDisposition, null)
            fileName = URLDecoder.decode(fileName, "UTF-8")
            currentWebView.longSnackbar(fileName, getString(R.string.action_download)) {
                Download.start(this, downloadUrl, fileName)
            }
        }
    }

    private fun saveImage(webPic: String) {
        this.webPic = webPic
        val path = ACache.get().getAsString(imagePathKey)
        if (path.isNullOrEmpty()) {
            selectSaveFolder()
        } else {
            viewModel.saveImage(webPic, path)
        }
    }

    private fun selectSaveFolder() {
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(imagePathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        saveImage.launch {
            otherActions = default
        }
    }

    override fun finish() {
        SourceVerificationHelp.checkResult(viewModel.sourceOrigin)
        super.finish()
    }

    override fun onDestroy() {
        WebViewPool.release(pooledWebView)
        super.onDestroy()
    }

    inner class CustomWebChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            binding.progressBar.setDurProgress(newProgress)
            binding.progressBar.gone(newProgress == 100)
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            binding.llView.invisible()
            binding.customWebView.addView(view)
            customWebViewCallback = callback
        }

        override fun onHideCustomView() {
            binding.customWebView.removeAllViews()
            binding.llView.visible()
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        /* 监听网页日志 */
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            viewModel.source?.let { source ->
                if (sessionShowWebLog) {
                    val messageLevel = consoleMessage.messageLevel().name
                    val message = consoleMessage.message()
                    AppLog.put("${source.getTag()}${messageLevel}: $message",
                        NoStackTraceException("\n${message}\n- Line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}"))
                    return true
                }
            }
            return false
        }

    }

    inner class CustomWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            request?.let {
                return shouldOverrideUrlLoading(it.url)
            }
            return true
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            url?.let {
                return shouldOverrideUrlLoading(Uri.parse(it))
            }
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (needClearHistory) {
                needClearHistory = false
                currentWebView.clearHistory()
            }
            super.onPageStarted(view, url, favicon)
        }
        
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            url?.let {
                CookieStore.setCookie(it, CookieStore.getWebCookie(it))
            }
            view?.title?.let { title ->
                if (title != url && title != view.url && title.isNotBlank()) {
                    binding.titleBar.title = title
                } else {
                    binding.titleBar.title = intent.getStringExtra("title")
                }
                view.evaluateJavascript("!!window._cf_chl_opt") {
                    if (it == "true") {
                        isCloudflareChallenge = true
                    } else if (isCloudflareChallenge && viewModel.sourceVerificationEnable) {
                        viewModel.saveVerificationResult(currentWebView) {
                            finish()
                        }
                    }
                }
            }
        }

        private fun shouldOverrideUrlLoading(url: Uri): Boolean {
            return when (url.scheme) {
                "http", "https" -> false
                "legado", "yuedu" -> {
                    startActivity<OnLineImportActivity> {
                        data = url
                    }
                    true
                }

                else -> {
                    binding.root.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                        openUrl(url)
                    }
                    true
                }
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?
        ) {
            handler?.proceed()
        }

    }

}