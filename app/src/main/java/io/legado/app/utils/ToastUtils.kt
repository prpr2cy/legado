@file:Suppress("unused")

package io.legado.app.utils

import android.annotation.SuppressLint
import android.os.Handler 
import android.os.Looper 
import android.view.View
import android.widget.TextView
import android.widget.Toast
import android.content.Context
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import splitties.views.inflate

private var toast: Toast? = null
private var toastLegacy: Toast? = null
private val toastHandler = Handler(Looper.getMainLooper())
private val toastQueue = ArrayDeque<ToastTask>()
private var toastQueued = false

private data class ToastTask(val message: CharSequence, val duration: Int)

fun Context.toastOnUi(message: Int, duration: Int = Toast.LENGTH_SHORT) {
    toastOnUi(getString(message), duration)
}

@SuppressLint("InflateParams")
@Suppress("DEPRECATION")
fun Context.toastOnUi(message: CharSequence?, duration: Int = Toast.LENGTH_SHORT) {
    toastHandler.post {
        synchronized(toastQueue) { toastQueue += ToastTask(message ?: return@post, duration) }
        drainQueue(this)
    }
}

private fun drainQueue(context: Context) {
    synchronized(toastQueue) {
        if (toastQueued) return
        toastQueued = true
    }
    toastHandler.post(object : Runnable {
        override fun run() {
            val task: ToastTask
            synchronized(toastQueue) {
                task = toastQueue.removeFirstOrNull() ?: run {
                    toastQueued = false
                    return
                }
            }
            kotlin.runCatching {
                val toastView = context.inflate<View>(R.layout.view_toast)
                val cardView = toastView.findViewById<CardView>(R.id.cv_content)
                cardView.setCardBackgroundColor(context.bottomBackground)
                val isLight = ColorUtils.isColorLight(context.bottomBackground)
                val textView = toastView.findViewById<TextView>(R.id.tv_text)
                textView.setTextColor(context.getPrimaryTextColor(isLight))
                textView.text = task.message
                Toast(context).apply {
                    view = toastView
                    duration = task.duration
                    show()
                }
            }
            toastHandler.postDelayed(this, 500)
        }
    })
}

fun Context.toastOnUiLegacy(message: CharSequence) {
    runOnUI {
        kotlin.runCatching {
            if (toastLegacy == null || BuildConfig.DEBUG || AppConfig.recordLog) {
                toastLegacy = Toast.makeText(this, message, Toast.LENGTH_SHORT)
            } else {
                toastLegacy?.setText(message)
                toastLegacy?.duration = Toast.LENGTH_SHORT
            }
            toastLegacy?.show()
        }
    }
}

fun Context.longToastOnUi(message: Int) {
    toastOnUi(message, Toast.LENGTH_LONG)
}

fun Context.longToastOnUi(message: CharSequence?) {
    toastOnUi(message, Toast.LENGTH_LONG)
}

fun Context.longToastOnUiLegacy(message: CharSequence) {
    runOnUI {
        kotlin.runCatching {
            if (toastLegacy == null || BuildConfig.DEBUG || AppConfig.recordLog) {
                toastLegacy = Toast.makeText(this, message, Toast.LENGTH_LONG)
            } else {
                toastLegacy?.setText(message)
                toastLegacy?.duration = Toast.LENGTH_LONG
            }
            toastLegacy?.show()
        }
    }
}

fun Fragment.toastOnUi(message: Int) = requireActivity().toastOnUi(message)

fun Fragment.toastOnUi(message: CharSequence) = requireActivity().toastOnUi(message)

fun Fragment.longToast(message: Int) = requireContext().longToastOnUi(message)

fun Fragment.longToast(message: CharSequence) = requireContext().longToastOnUi(message)
