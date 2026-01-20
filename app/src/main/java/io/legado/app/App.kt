package io.legado.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
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

    // GMS Provider 安装标记的键名
    private companion object {
        private const val PREF_GMS_PROVIDER_INSTALLED = "gms_provider_installed"
        private const val PREF_LAST_ATTEMPTED_VERSION = "last_attempted_version"
    }

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
     * 尝试在安装了GMS的设备上(GMS或者MicroG)使用GMS内置的Conscrypt
     * 作为首选JCE提供程序，而使Okhttp在低版本Android上
     * 能够启用TLSv1.3
     * https://f-droid.org/zh_Hans/2020/05/29/android-updates-and-tls-connections.html
     * https://developer.android.google.cn/reference/javax/net/ssl/SSLSocket
     *
     * 优化：只在应用安装后或版本更新后首次启动时尝试
     * @param context
     * @return
     */
    private fun installGmsTlsProvider(context: Context) {
        val prefs = defaultSharedPreferences
        val currentVersion = BuildConfig.VERSION_CODE

        // 获取上次尝试的版本号
        val lastAttemptedVersion = prefs.getInt(PREF_LAST_ATTEMPTED_VERSION, -1)

        // 如果已经尝试过当前版本，直接跳过
        if (lastAttemptedVersion == currentVersion) {
            AppLog.put("GMS provider installation already attempted for version $currentVersion, skipping")
            return
        }

        // 尝试安装 GMS Provider
        try {
            val gms = context.createPackageContext(
                "com.google.android.gms",
                CONTEXT_INCLUDE_CODE or CONTEXT_IGNORE_SECURITY
            )
            val result = gms.classLoader
                .loadClass("com.google.android.gms.common.security.ProviderInstallerImpl")
                .getMethod("insertProvider", Context::class.java)
                .invoke(null, gms) as Int

            if (result > 0) {
                AppLog.put("Conscrypt provider inserted at position: $result")
                // 标记为已安装
                prefs.edit().putBoolean(PREF_GMS_PROVIDER_INSTALLED, true).apply()
            }
        } catch (e: Exception) {
            // 预期行为：无GMS的设备会抛出异常
            // 只记录一次日志，避免重复记录
            if (lastAttemptedVersion != currentVersion) {
                AppLog.put("GMS provider not available", e)
            }
        } finally {
            // 无论成功与否，都记录已经尝试过当前版本
            prefs.edit().putInt(PREF_LAST_ATTEMPTED_VERSION, currentVersion).apply()
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