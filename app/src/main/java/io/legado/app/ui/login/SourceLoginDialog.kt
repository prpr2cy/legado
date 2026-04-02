package io.legado.app.ui.login

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.data.entities.rule.RowUi.Type
import io.legado.app.databinding.DialogLoginBinding
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.databinding.ItemSourceEditBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.openUrl
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import splitties.views.onClick

class SourceLoginDialog : BaseDialogFragment(R.layout.dialog_login, true),
    SourceLoginJsExtensions.Callback {

    private val binding by viewBinding(DialogLoginBinding::bind)
    private val viewModel by activityViewModels<SourceLoginViewModel>()
    private var rowUis: List<RowUi>? = null
    private var rowUiName = arrayListOf<String>()
    private var loginUrl: String? = null
    private var oKToClose = false
    private var hasChange = false
    private val sourceLoginJsExtensions by lazy {
        SourceLoginJsExtensions(
            activity as AppCompatActivity,
            viewModel.source,
            this
        )
    }

    override fun upUiData(data: Map<String, Any?>?) {
        lifecycleScope.launch(Main) {
            runCatching {
                handleUpUiData(data)
            }.onFailure { e ->
                AppLog.put("upLoginData Error: ${e.localizedMessage}", e)
            }
        }
    }

    override fun reUiView() {
        lifecycleScope.launch(Main) {
            runCatching {
                handleReUiView()
            }.onFailure { e ->
                AppLog.put("reLoginView Error: ${e.localizedMessage}", e)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun handleUpUiData(data: Map<String, Any?>? = null) {
        hasChange = true
        val loginInfo = viewModel.loginInfo
        if (data == null) {
            rowUis?.forEachIndexed { index, rowUi ->
                when (rowUi.type) {
                    Type.text, Type.password -> {
                        val rowView = binding.root.findViewById<View>(index + 1000) ?: return@forEachIndexed
                        val itemBinding = ItemSourceEditBinding.bind(rowView)
                        val text = rowUi.default ?: ""
                        itemBinding.editText.setText(text)
                        loginInfo[rowUi.name] = text
                    }
                }
            }
        } else {
            data.forEach { (key, value) ->
                val index = rowUiName.indexOf(key)
                if (index == -1) {
                    loginInfo[key] = value?.toString() ?: ""
                    return@forEach
                }
                val rowUi = rowUis?.getOrNull(index) ?: return@forEach
                when (rowUi.type) {
                    Type.text, Type.password -> {
                        val rowView = binding.root.findViewById<View>(index + 1000) ?: return@forEach
                        val itemBinding = ItemSourceEditBinding.bind(rowView)
                        val text = value?.toString() ?: rowUi.default ?: ""
                        itemBinding.editText.setText(text)
                        loginInfo[rowUi.name] = text
                    }
                }
            }
        }
    }

    private suspend fun handleReUiView() {
        val source = viewModel.source ?: return
        val loginUiStr = source.loginUi ?: return
        hasChange = true

        val newRowUis = withContext(IO) {
            parseLoginUi(loginUiStr)
        } ?: return

        withContext(Main) {
            TransitionManager.beginDelayedTransition(
                binding.flexbox,
                AutoTransition().apply {
                    duration = 250
                    interpolator = android.view.animation.DecelerateInterpolator()
                }
            )

            binding.flexbox.removeAllViews()
            rowUiName.clear()
            rowUis = newRowUis
            rowUiBuilder(source, newRowUis)
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val source = viewModel.source ?: return
        loginUrl = source.getLoginJs()
        val loginUiStr = source.loginUi ?: return

        lifecycleScope.launch(Main) {
            rowUis = withContext(IO) {
                parseLoginUi(loginUiStr)
            }
            rowUiBuilder(source, rowUis)
            setButtonUi(source, rowUis)
        }

        binding.toolBar.apply {
            setBackgroundColor(primaryColor)
            title = getString(R.string.login_source, source.getTag())
            inflateMenu(R.menu.source_login)
            menu.applyTint(requireContext())
        }
    }

    private suspend fun parseLoginUi(loginUiStr: String): List<RowUi>? {
        return try {
            val loginUiJson = when {
                loginUiStr.startsWith("@js:", true) -> evalUiJs(loginUiStr.substring(4))
                loginUiStr.startsWith("<js>", true) -> {
                    val endIndex = loginUiStr.lastIndexOf("<")
                    if (endIndex > 4) evalUiJs(loginUiStr.substring(4, endIndex)) else loginUiStr
                }
                else -> loginUiStr
            }
            GSON.fromJsonArray<RowUi>(loginUiJson).getOrNull()
        } catch (e: Exception) {
            AppLog.put("parseLoginUi error: ${e.message}", e)
            null
        }
    }

    private suspend fun evalUiJs(jsCode: String): String? {
        val source = viewModel.source ?: return null
        val loginJS = loginUrl ?: ""

        return try {
            source.evalJS("$loginJS\n$jsCode") {
                put("java", sourceLoginJsExtensions)
                put("result", viewModel.loginInfo)
                put("book", viewModel.book)
                put("chapter", viewModel.chapter)
            }.toString()
        } catch (e: Exception) {
            AppLog.put("${source.getTag()} loginUi error", e)
            null
        }
    }

    @SuppressLint("SetTextI18n")
    private fun rowUiBuilder(source: BaseSource, rowUis: List<RowUi>?) {
        val loginInfo = viewModel.loginInfo
        rowUis?.forEachIndexed { index, rowUi ->
            rowUiName.add(rowUi.name)
            when (rowUi.type) {
                Type.text, Type.password -> ItemSourceEditBinding.inflate(
                    layoutInflater, binding.flexbox, false
                ).apply {
                    binding.flexbox.addView(root)
                    rowUi.style().apply(root)
                    root.id = index + 1000
                    textInputLayout.hint = rowUi.name

                    if (rowUi.type == Type.password) {
                        editText.inputType =
                            InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT
                    }
                    editText.setText(loginInfo[rowUi.name] ?: rowUi.default ?: "")
                }

                Type.button -> ItemFilletTextBinding.inflate(
                    layoutInflater, binding.flexbox, false
                ).apply {
                    binding.flexbox.addView(root)
                    rowUi.style().apply(root)
                    root.id = index + 1000
                    textView.text = rowUi.name
                    textView.setPadding(16.dpToPx())
                    root.onClick {
                        handleButtonClick(source, rowUi, getLoginInfo(rowUis))
                    }
                }
            }
        }
    }

    private fun setButtonUi(source: BaseSource, rowUis: List<RowUi>?) {
        binding.toolBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_ok -> {
                    oKToClose = true
                    login(source, getLoginInfo(rowUis))
                }
                R.id.menu_show_login_header -> showLoginHeader(source)
                R.id.menu_del_login_header -> source.removeLoginHeader()
                R.id.menu_del_login_info -> {
                    viewModel.loginInfo.clear()
                    source.removeLoginInfo()
                    handleUpUiData()
                }
                R.id.menu_log -> showDialogFragment<AppLogDialog>()
            }
            true
        }
    }

    private fun showLoginHeader(source: BaseSource) {
        alert {
            setTitle(R.string.login_header)
            source.getLoginHeader()?.let { header ->
                setMessage(header)
                positiveButton(R.string.copy_text) { appCtx.sendToClip(header) }
            }
        }
    }

    private fun handleButtonClick(
        source: BaseSource,
        rowUi: RowUi,
        loginInfo: MutableMap<String, String>
    ) {
        lifecycleScope.launch(IO) {
            if (rowUi.action.isAbsUrl()) {
                context?.openUrl(rowUi.action!!)
            } else if (rowUi.action != null) {
                val loginJS = loginUrl ?: return@launch
                val buttonFunctionJS = rowUi.action
                try {
                    source.evalJS("$loginJS\n$buttonFunctionJS") {
                        put("java", sourceLoginJsExtensions)
                        put("result", loginInfo)
                        put("book", viewModel.book)
                        put("chapter", viewModel.chapter)
                    }
                } catch (e: Exception) {
                    AppLog.put("LoginUI Button ${rowUi.name} JavaScript error", e)
                }
            }
        }
    }

    private fun getLoginInfo(rowUis: List<RowUi>?): MutableMap<String, String> {
        val loginInfo = viewModel.loginInfo
        rowUis?.forEachIndexed { index, rowUi ->
            when (rowUi.type) {
                Type.text, Type.password -> {
                    val rowView = binding.root.findViewById<View>(index + 1000) ?: return@forEachIndexed
                    val text = ItemSourceEditBinding.bind(rowView).editText.text?.toString()
                    loginInfo[rowUi.name] = text ?: rowUi.default ?: ""
                }
            }
        }
        return loginInfo
    }

    private fun login(source: BaseSource, loginInfo: MutableMap<String, String>) {
        lifecycleScope.launch(IO) {
            if (loginInfo.isEmpty()) {
                source.removeLoginInfo()
                withContext(Main) {
                    dismiss()
                }
            } else if (source.putLoginInfo(GSON.toJson(loginInfo))) {
                try {
                    val loginJS = loginUrl ?: return@launch
                    val buttonFunctionJS = "if (typeof login=='function') { login.apply(this); } else { throw('Function login not implements!!!') }"
                    source.evalJS("$loginJS\n$buttonFunctionJS") {
                        put("java", sourceLoginJsExtensions)
                        put("result", loginInfo)
                        put("book", viewModel.book)
                        put("chapter", viewModel.chapter)
                    }
                    context?.toastOnUi(R.string.success)
                    withContext(Main) {
                        dismiss()
                    }
                } catch (e: Exception) {
                    AppLog.put("登录出错\n${e.localizedMessage}", e)
                    context?.toastOnUi("登录出错\n${e.localizedMessage}")
                    e.printOnDebug()
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (!oKToClose && hasChange) {
            val loginInfo = viewModel.loginInfo
            if (loginInfo.isEmpty()) {
                viewModel.source?.removeLoginInfo()
            } else {
                viewModel.source?.putLoginInfo(GSON.toJson(loginInfo))
            }
        }
        super.onDismiss(dialog)
        activity?.finish()
    }

}