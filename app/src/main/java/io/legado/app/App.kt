package io.legado.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.AppCompatEditText
import com.github.liuyueyi.quick.transfer.ChineseUtils
import com.github.liuyueyi.quick.transfer.constants.TransType
import com.jeremyliao.liveeventbus.LiveEventBus
import io.legado.app.base.AppContextWrapper
import io.legado.app.constant.AppConst.channelIdDownload
import io.legado.app.constant.AppConst.channelIdReadAloud
import io.legado.app.constant.AppConst.channelIdWeb
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.AppWebDav
import io.legado.app.help.CrashHandler
import io.legado.app.help.DefaultData
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.RuleBigDataHelp
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig.applyDayNight
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.Cronet
import io.legado.app.help.http.ObsoleteUrlFactory
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.storage.Backup
import io.legado.app.model.BookCover
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.net.URL
import java.util.concurrent.TimeUnit

class App : Application() {

    private lateinit var oldConfig: Configuration

    override fun onCreate() {
        super.onCreate()
        oldConfig = Configuration(resources.configuration)
        CrashHandler(this)
        // 预下载Cronet so
        Cronet.preDownload()
        createNotificationChannels()
        applyDayNight(this)
        LiveEventBus.config()
            .lifecycleObserverAlwaysActive(true)
            .autoClear(false)
        registerActivityLifecycleCallbacks(LifecycleHelp)
        defaultSharedPreferences.registerOnSharedPreferenceChangeListener(AppConfig)
        DefaultData.upVersion()

        // 在 Android 8.0-8.1 安装修复
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.O_MR1) {
            installAndroid8SelectionFix()
        }

        Coroutine.async {
            withContext(Dispatchers.IO) {
                // 1. 设置 URLStreamHandlerFactory（安全调用）
                try {
                    URL.setURLStreamHandlerFactory(ObsoleteUrlFactory(okHttpClient))
                } catch (e: Error) {
                    AppLog.put("URLStreamHandlerFactory already set by other module", e)
                }

                // 2. 安装 GMS TLS Provider
                installGmsTlsProvider(appCtx)

                // 3. 初始化封面
                BookCover.toString()

                // 4. 清除过期数据
                appDb.cacheDao.clearDeadline(System.currentTimeMillis())
                if (getPrefBoolean(PreferKey.autoClearExpired, true)) {
                    val clearTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
                    appDb.searchBookDao.clearExpired(clearTime)
                }
                RuleBigDataHelp.clearInvalid()
                BookHelp.clearInvalidCache()
                Backup.clearCache()

                // 5. 初始化简繁转换引擎
                when (AppConfig.chineseConverterType) {
                    1 -> ChineseUtils.preLoad(true, TransType.TRADITIONAL_TO_SIMPLE)
                    2 -> ChineseUtils.preLoad(true, TransType.SIMPLE_TO_TRADITIONAL)
                }

                // 6. 调整排序序号
                SourceHelp.adjustSortNumber()

                // 7. 同步阅读记录
                if (AppConfig.syncBookProgress) {
                    AppWebDav.downloadAllBookProgress()
                }
            }
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppContextWrapper.wrap(base))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val diff = newConfig.diff(oldConfig)
        if ((diff and ActivityInfo.CONFIG_UI_MODE) != 0) {
            applyDayNight(this)
        }
        oldConfig = Configuration(newConfig)
    }

    /**
     * Android 8.0-8.1 键盘选择文本修复
     */
    private fun installAndroid8SelectionFix() {
        try {
            val layoutInflater = LayoutInflater.from(this)

            // 保存原始的 Factory2
            val originalFactory2 = layoutInflater.factory2

            // 创建包装器
            val wrapper = object : LayoutInflater.Factory2 {
                override fun onCreateView(
                    parent: View?,
                    name: String,
                    context: Context,
                    attrs: AttributeSet
                ): View? {
                    if (name == "EditText" ||
                        name == "android.widget.EditText" ||
                        name == "androidx.appcompat.widget.AppCompatEditText") {
                        // 直接创建 SafeEditText，使用默认样式
                        return SafeEditText(context, attrs, android.R.attr.editTextStyle)
                    }

                    // 其他 View 走原始流程
                    return originalFactory2?.onCreateView(parent, name, context, attrs)
                }

                override fun onCreateView(
                    name: String,
                    context: Context,
                    attrs: AttributeSet
                ): View? {
                    return onCreateView(null, name, context, attrs)
                }
            }

            // 关键修复：同时设置 factory2 和 factory，避免链式断裂
            layoutInflater.factory2 = wrapper
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // 在 API 21+ 上，factory 和 factory2 是分开的
                // 某些库会先检查 factory，所以需要同时设置
                layoutInflater.factory = wrapper
            }

        } catch (e: Exception) {
            // 修复失败不影响应用运行，记录详细的错误信息
            AppLog.putDebug("Failed to install Android 8.0-8.1 selection fix", e)
        }
    }

    /**
     * 尝试在安装了GMS的设备上(GMS或者MicroG)使用GMS内置的Conscrypt
     * 作为首选JCE提供程序，而使Okhttp在低版本Android上
     * 能够启用TLSv1.3
     * https://f-droid.org/zh_Hans/2020/05/29/android-updates-and-tls-connections.html
     * https://developer.android.google.cn/reference/javax/net/ssl/SSLSocket
     *
     * @param context
     * @return
     */
    private fun installGmsTlsProvider(context: Context) {
        try {
            val gms = context.createPackageContext(
                "com.google.android.gms",
                CONTEXT_INCLUDE_CODE or CONTEXT_IGNORE_SECURITY
            )
            gms.classLoader
                .loadClass("com.google.android.gms.common.security.ProviderInstallerImpl")
                .getMethod("insertProvider", Context::class.java)
                .invoke(null, gms)
        } catch (e: Exception) {
            AppLog.put("GMS provider not available", e)
        }
    }

    /**
     * 创建通知ID
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val downloadChannel = NotificationChannel(
            channelIdDownload,
            getString(R.string.action_download),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            importance = NotificationManager.IMPORTANCE_LOW
        }

        val readAloudChannel = NotificationChannel(
            channelIdReadAloud,
            getString(R.string.read_aloud),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            importance = NotificationManager.IMPORTANCE_LOW
        }

        val webChannel = NotificationChannel(
            channelIdWeb,
            getString(R.string.web_service),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            importance = NotificationManager.IMPORTANCE_LOW
        }

        // 向notification manager 提交channel
        notificationManager.createNotificationChannels(
            listOf(
                downloadChannel,
                readAloudChannel,
                webChannel
            )
        )
    }
}

/**
 * 安全的 EditText，修复 Android 8.0-8.1 键盘选择文本崩溃
 * 核心修复：复制/剪切后立即清空选区，光标保留在选区结尾位置
 * 关键修复：使用 postDelayed 并检查选区状态，确保修复生效
 */
class SafeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    /**
     * 检查当前是否有选区
     */
    private fun hasActiveSelection(): Boolean {
        return selectionStart != selectionEnd
    }

    /**
     * 重写文本上下文菜单项处理
     * 在复制/剪切后立即清空选区，光标保留在选区结尾位置
     */
    override fun onTextContextMenuItem(id: Int): Boolean {
        val consumed = super.onTextContextMenuItem(id)

        // 仅在 Android 8.0-8.1 处理复制和剪切操作
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.O_MR1) {
            when (id) {
                android.R.id.copy,       // 复制
                android.R.id.cut -> {    // 剪切
                    // 保存选区结尾位置
                    val selectionEnd = selectionEnd
                    // 关键修复：使用 postDelayed 并检查选区状态
                    // 16ms 确保系统 IME 事件处理完成，但又足够快
                    postDelayed({
                        // 只有在仍有选区时才清除
                        if (hasActiveSelection()) {
                            try {
                                setSelection(selectionEnd)
                            } catch (e: Exception) {
                                // 安全处理：如果 selectionEnd 无效，设置为文本长度
                                setSelection(text?.length ?: 0)
                                // 记录错误信息
                                AppLog.putDebug("SafeEditText setSelection failed", e)
                            }
                        }
                    }, 16L)
                }
            }
        }

        return consumed
    }
}