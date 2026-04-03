package io.legado.app.ui.login

import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.textfield.TextInputLayout
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

    companion object {
        private const val VIEW_ID_OFFSET = 1000
    }

    private val binding by viewBinding(DialogLoginBinding::bind)
    private val viewModel by activityViewModels<SourceLoginViewModel>()
    private var loginUi: String? = null
    private var rowUis: List<RowUi>? = null
    private var rowUiName = arrayListOf<String>()
    private var loginUrl: String? = null
    private var isSame = false
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

    private fun handleUpUiData(data: Map<String, Any?>? = null) {
        val loginInfo = viewModel.loginInfo
        hasChange = true

        if (data == null) {
            rowUis?.forEachIndexed { index, rowUi ->
                rowUi ?: return@forEachIndexed
                when (rowUi.type) {
                    Type.text, Type.password -> {
                        val rowView = binding.root.findViewById<View>(index + VIEW_ID_OFFSET) ?: return@forEachIndexed
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
                        val rowView = binding.root.findViewById<View>(index + VIEW_ID_OFFSET) ?: return@forEach
                        val itemBinding = ItemSourceEditBinding.bind(rowView)
                        val text = value?.toString() ?: rowUi.default ?: ""
                        itemBinding.editText.setText(text)
                        loginInfo[rowUi.name] = text
                    }
                    else -> {
                        loginInfo[key] = value?.toString() ?: ""
                    }
                }
            }
        }
    }

    private suspend fun handleReUiView() {
        val source = viewModel.source ?: return
        val loginUiStr = source.loginUi ?: return

        val newRowUis = withContext(IO) {
            parseLoginUi(loginUiStr)
        } ?: return

        if (isSame || newRowUis == null) {
            isSame = false
            return
        }

        hasChange = true

        withContext(Main) {
            TransitionManager.beginDelayedTransition(
                binding.flexbox,
                AutoTransition().apply {
                    duration = 250
                    interpolator = DecelerateInterpolator()
                }
            )

            reUiBuilder(source, newRowUis)
        }
    }

    private fun reUiBuilder(source: BaseSource, newRowUis: List<RowUi>) {
        val loginInfo = viewModel.loginInfo
        val oldRowUis: MutableList<RowUi?>? = rowUis?.toMutableList()
        val newRowUiName = ArrayList<String>(newRowUis.size)

        newRowUis.forEachIndexed { index, newRowUi ->
            val oldRowUi = oldRowUis.getOrNull(index)
            when {
                oldRowUi == null -> {
                    val view = createView(source, rowUi, index, loginInfo)
                    binding.flexbox.addView(view, index)
                    return@forEachIndexed
                }

                oldRowUi.type != newRowUi.type -> {
                    val view = createView(source, rowUi, index, loginInfo)
                    binding.flexbox.removeViewAt(index)
                    binding.flexbox.addView(view, index)
                    return@forEachIndexed
                }

                else -> {
                    binding.flexbox.getChildAt(index)?.let { view ->
                        updateView(view, newRowUi, loginInfo)
                    }
                }
            }
            newRowUiName.add(newRowUi.name)
        }

        for (i in (binding.flexbox.childCount - 1) downTo newRowUis.size) {
            binding.flexbox.removeViewAt(i)
        }

        rowUis = newRowUis
        rowUiName.clear()
        rowUiName.addAll(newRowUiName)
    }

    private fun updateView(view: View, rowUi: RowUi, loginInfo: MutableMap<String, String>) {
        when (rowUi.type) {
            Type.text, Type.password -> {
                val itemBinding = ItemSourceEditBinding.bind(view)
                itemBinding.apply {
                    rowUi.style().apply(root)
                    textInputLayout.hint = rowUi.name
                    val text = loginInfo[rowUi.name] ?: rowUi.default ?: ""
                    editText.setText(text)
                }
            }

            Type.button -> {
                val itemBinding = ItemFilletTextBinding.bind(view)
                itemBinding.apply {
                    rowUi.style().apply(root)
                    textView.text = rowUi.name
                    root.onClick {
                        handleButtonClick(source, rowUi, getLoginInfo())
                    }
                }
            }
        }
    }

    private fun createView(source: BaseSource, rowUi: RowUi, index: Int, loginInfo: MutableMap<String, String>): View {
        return when (rowUi.type) {
            Type.text, Type.password -> {
                val itemBinding = ItemSourceEditBinding.inflate(
                    layoutInflater, binding.flexbox, false
                )
                itemBinding.apply {
                    rowUi.style().apply(root)
                    root.id = index + VIEW_ID_OFFSET
                    textInputLayout.apply {
                        isExpandedHintEnabled = false
                        hint = rowUi.name
                    }
                    if (rowUi.type == Type.password) {
                        textInputLayout.endIconMode =
                            TextInputLayout.END_ICON_PASSWORD_TOGGLE
                        editText.inputType =
                            InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT
                    }
                    val text = loginInfo[rowUi.name] ?: rowUi.default ?: ""
                    editText.setText(text)
                }
                itemBinding.root
            }

            Type.button -> {
                val itemBinding = ItemFilletTextBinding.inflate(
                    layoutInflater, binding.flexbox, false
                )
                itemBinding.apply {
                    rowUi.style().apply(root)
                    root.id = index + VIEW_ID_OFFSET
                    textView.text = rowUi.name
                    textView.setPadding(16.dpToPx())
                    root.onClick {
                        handleButtonClick(source, rowUi, getLoginInfo())
                    }
                }
                itemBinding.root
            }

            else -> {
                val itemBinding = ItemFilletTextBinding.inflate(
                    layoutInflater, binding.flexbox, false
                )
                itemBinding.root
            }
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

            if (loginUiJson == loginUi && rowUis != null) {
                isSame = true
                rowUis
            } else {
                loginUi = loginUiJson
                isSame = false
                GSON.fromJsonArray<RowUi>(loginUiJson).getOrNull()
            }
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
            firstUiBuilder(source, rowUis)
            setButtonUi(source, rowUis)
        }

        binding.toolBar.apply {
            setBackgroundColor(primaryColor)
            title = getString(R.string.login_source, source.getTag())
            inflateMenu(R.menu.source_login)
            menu.applyTint(requireContext())
        }
    }

    private fun firstUiBuilder(source: BaseSource, rowUis: List<RowUi>?) {
        val loginInfo = viewModel.loginInfo
        binding.flexbox.removeAllViews()
        rowUiName.clear()

        rowUis?.forEachIndexed { index, rowUi ->
            rowUiName.add(rowUi.name)
            val view = createView(source, rowUi, index, loginInfo)
            binding.flexbox.addView(view)
        }
    }

    private fun setButtonUi(source: BaseSource, rowUis: List<RowUi>?) {
        binding.toolBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_ok -> {
                    oKToClose = true
                    login(source, getLoginInfo())
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

    private fun getLoginInfo(): MutableMap<String, String> {
        val loginInfo = viewModel.loginInfo
        rowUis?.forEachIndexed { index, rowUi ->
            rowUi ?: return@forEachIndexed
            when (rowUi.type) {
                Type.text, Type.password -> {
                    val rowView = binding.root.findViewById<View>(index + VIEW_ID_OFFSET)
                        ?: return@forEachIndexed
                    val text = ItemSourceEditBinding.bind(rowView).editText.text?.toString()
                    loginInfo[rowUi.name] = text ?: rowUi.default ?: ""
                }
            }
        }
        return loginInfo
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
                    val buttonFunctionJS = "if (typeof login === 'function') { login.apply(this); } " +
                        "else { throw('Function login not implemented!') }"
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