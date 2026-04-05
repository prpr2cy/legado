package io.legado.app.ui.login

import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.Job
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
    private var loginUrl: String? = null
    private var rowUis: List<RowUi>? = null
    private var rowUiName = arrayListOf<String>()
    private var oKToClose = false
    private var hasChange = false
    private var prepareJob: Job? = null

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
                        val view = binding.root.findViewById<View>(index + VIEW_ID_OFFSET)
                        view ?: return@forEachIndexed
                        val itemBinding = ItemSourceEditBinding.bind(view)
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
                        val view = binding.root.findViewById<View>(index + VIEW_ID_OFFSET)
                        view ?: return@forEach
                        val itemBinding = ItemSourceEditBinding.bind(view)
                        val text = value?.toString() ?: rowUi.default ?: ""
                        itemBinding.editText.setText(text)
                        loginInfo[rowUi.name] = text
                    }

                    Type.button -> {
                        val view = binding.root.findViewById<View>(index + VIEW_ID_OFFSET)
                        view ?: return@forEach
                        val itemBinding = ItemFilletTextBinding.bind(view)
                        itemBinding.textView.text = value?.toString() ?: rowUi.name
                    }
                }
            }
        }
    }

    private suspend fun handleReUiView() {
        val source = viewModel.source ?: return
        loginUrl = source.getLoginJs()

        val newRowUis = withContext(IO) {
            val newLoginUi = source.loginUiJs()?.let { evalUiJs(it) } ?: source.loginUi
            newLoginUi ?: return@withContext null
            if (newLoginUi == loginUi) return@withContext null
            parseLoginUi(newLoginUi)?.also {
                loginUi = newLoginUi
            }
        } ?: return

        withContext(Main) {
            rowUiBuilder(source, newRowUis)

            for (i in binding.flexbox.childCount - 1 downTo newRowUis.size) {
                binding.flexbox.removeViewAt(i)
            }

            rowUis = newRowUis
            hasChange = true
        }
    }

    private suspend fun evalUiJs(loginUiJs: String): String? {
        val source = viewModel.source ?: return null
        val loginJS = loginUrl ?: ""

        return try {
            source.evalJS("$loginJS\n$loginUiJs") {
                put("java", sourceLoginJsExtensions)
                put("result", viewModel.loginInfo)
                put("book", viewModel.book)
                put("chapter", viewModel.chapter)
            }.toString()
        } catch (e: Exception) {
            AppLog.put("${source.getTag()} loginUi js error", e)
            null
        }
    }

    private fun parseLoginUi(loginUi: String?): List<RowUi>? {
        return GSON.fromJsonArray<RowUi>(loginUi).onFailure {
            AppLog.put("loginUi json parse error: " + it.localizedMessage, it)
        }.getOrNull()
    }

    override fun onStart() {
        super.onStart()
        setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val source = viewModel.source ?: return
        loginUrl = source.getLoginJs()

        prepareJob = lifecycleScope.launch(Main) {
            binding.root.visibility = View.INVISIBLE
            rowUis = withContext(IO) {
                loginUi = source.loginUiJs()?.let { evalUiJs(it) } ?: source.loginUi
                loginUrl ?: return@withContext null
                parseLoginUi(loginUi)
            }
            rowUiBuilder(source, rowUis)
            setMenuUi(source)
            binding.root.visibility = View.VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        prepareJob?.cancel()
    }

    private fun rowUiBuilder(source: BaseSource, rowUis: List<RowUi>?) {
        val loginInfo = viewModel.loginInfo
        var unused = 0
        rowUiName.clear()
        binding.flexbox.clearFocus()

        rowUis?.forEachIndexed { index, rowUi ->
            when (rowUi.type) {
                Type.text, Type.password -> {
                    val itemBinding = ItemSourceEditBinding.inflate(
                        layoutInflater, binding.flexbox, false
                    )
                    itemBinding.apply {
                        rowUi.style().apply(root)
                        root.id = index + VIEW_ID_OFFSET
                        textInputLayout.hint = rowUi.name
                        if (rowUi.type == Type.password) {
                            textInputLayout.endIconMode =
                                TextInputLayout.END_ICON_PASSWORD_TOGGLE
                            editText.inputType =
                                InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT
                        }
                        editText.minHeight = 48.dpToPx()
                        val text = loginInfo[rowUi.name] ?: rowUi.default ?: ""
                        editText.setText(text)
                    }
                    binding.flexbox.addView(itemBinding.root, index - unused)
                    rowUiName.add(rowUi.name)
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
                    binding.flexbox.addView(itemBinding.root, index - unused)
                    rowUiName.add(rowUi.name)
                }

                else -> unused++
            }
        }

        binding.flexbox.apply {
            isFocusable = true 
            isFocusableInTouchMode = true 
            requestFocus()
        }
    }

    private fun setMenuUi(source: BaseSource) {
        binding.toolBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_ok -> {
                    oKToClose = true
                    login(source, getLoginInfo())
                }
                R.id.menu_show_login_header -> alert {
                    setTitle(R.string.login_header)
                    source.getLoginHeader()?.let { header ->
                        setMessage(header)
                        positiveButton(R.string.copy_text) { appCtx.sendToClip(header) }
                    }
                }
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

        binding.toolBar.apply {
            setBackgroundColor(primaryColor)
            title = getString(R.string.login_source, source.getTag())
            inflateMenu(R.menu.source_login)
            menu.applyTint(requireContext())
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
                    AppLog.put("loginUI button ${rowUi.name} js error", e)
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