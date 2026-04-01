package io.legado.app.ui.login

import androidx.appcompat.app.AppCompatActivity
import io.legado.app.data.entities.BaseSource
import io.legado.app.utils.sendToClip
import java.lang.ref.WeakReference

@Suppress("unused")
class SourceLoginJsExtensions(
    private val activity: AppCompatActivity,
    private val source: BaseSource?,
    private val callback: Callback
) {
    private val activityRef: WeakReference<AppCompatActivity> = WeakReference(activity)
    private val sourceRef: WeakReference<BaseSource?> = WeakReference(source)
    private val callbackRef: WeakReference<Callback> = WeakReference(callback)
    interface Callback {
        fun upUiData(data: Map<String, Any?>?)
        fun reUiView()
        fun saveLoginData(): Boolean
    }

    @JvmOverloads
    fun upLoginData(data: Map<String, Any?>?) {
        callbackRef.get()?.upUiData(data)
    }

    fun reLoginView() {
        callbackRef.get()?.reUiView()
    }

    fun saveLoginInfo(): Boolean {
        return callbackRef.get()?.saveLoginData() ?: false
    }

    fun copyText(text: String) {
        activityRef.get()?.sendToClip(text)
    }
}