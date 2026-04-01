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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import splitties.views.onClick

class SourceLoginDialog : BaseDialogFragment(R.layout.dialog_login, true),
    SourceLoginJsExtensions.Callback {

    private val binding by viewBinding(DialogLoginBinding::bind)
    private val viewModel by activityViewModels<SourceLoginViewModel>()
    private var rowUis: List<RowUi>? = null
    private var rowUiName = arrayListOf<String>()
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
        try {
            activity?.runOnUiThread {
                handleUpUiData(data)
            }
        } catch (e: Exception) {
            AppLog.put("upLoginData Error: " + e.localizedMessage, e)
        }
    }

    override fun reUiView() {
        try {
            activity?.runOnUiThread {
                handleReUiView()
            }
        } catch (e: Exception) {
            AppLog.put("reLoginView Error: " + e.localizedMessage, e)
        }
    }

    override fun saveLoginData(): Boolean {
        return try {
            var result = false
            val loginData = getLoginData(rowUis)
            runBlocking(IO) {
                result = if (loginData.isNotEmpty()) {
                    viewModel.source?.putLoginInfo(GSON.toJson(loginData)) ?: false
                } else {
                    viewModel.source?.removeLoginHeader()
                    true
                }
            }
            result
        } catch (e: Exception) {
            AppLog.put("saveLoginData error", e)
            false 
        }
    }

    @SuppressLint("SetTextI18n")
    private fun handleUpUiData(data: Map<String, Any?>?) {
        hasChange = true
        val loginData = viewModel.loginInfo
        if (data == null) {
            rowUis?.forEachIndexed { index, rowUi ->
                when (rowUi.type) {
                    Type.text, Type.password -> {
                        val rowView = binding.root.findViewById<View>(index + 1000)
                        if (rowView != null) {
                            val itemBinding = ItemSourceEditBinding.bind(rowView)
                            val text = rowUi.default ?: ""
                            itemBinding.editText.setText(text)
                            loginData[rowUi.name] = text
                        }
                    }
                }
            }
        } else {
            data.forEach { (key, value) ->
                val index = rowUiName.indexOf(key)
                if (index != -1) {
                    val rowUi = rowUis?.getOrNull(index) ?: return@forEach
                    val rowView = binding.root.findViewById<View>(index + 1000) ?: return@forEach
                    when (rowUi.type) {
                        Type.text, Type.password -> {
                            val itemBinding = ItemSourceEditBinding.bind(rowView)
                            val text = value?.toString() ?: rowUi.default ?: ""
                            itemBinding.editText.setText(text)
                            loginData[rowUi.name] = text
                        }
                    }
                } else {
                    loginData[key] = value?.toString() ?: ""
                }
            }
        }
    }

    private fun handleReUiView() {
        val source = viewModel.source ?: return
        val loginUiStr = source.loginUi ?: return
        hasChange = true

        lifecycleScope.launch(Main) {
            withContext(IO) {
                val loginUiJson = if (loginUiStr.startsWith("@js:", true) || loginUiStr.startsWith("<js>", true)) {
                    evalUiJs(parseJsCode(loginUiStr))
                } else {
                    loginUiStr
                }
                rowUis = GSON.fromJsonArray<RowUi>(loginUiJson).getOrNull()
            }
            binding.flexbox.removeAllViews()
            rowUiName.clear()
            rowUiBuilder(source, rowUis)
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val source = viewModel.source ?: return
        val loginUiStr = source.loginUi ?: return

        lifecycleScope.launch(Main) {
            withContext(IO) {
                val loginUiJson = if (loginUiStr.startsWith("@js:", true) || loginUiStr.startsWith("<js>", true)) {
                    evalUiJs(parseJsCode(loginUiStr))
                } else {
                    loginUiStr
                }
                rowUis = GSON.fromJsonArray<RowUi>(loginUiJson).getOrNull()
            }
            rowUiBuilder(source, rowUis)
            setButtonUi(source, rowUis)
        }

        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.title = getString(R.string.login_source, source.getTag())
        binding.toolBar.inflateMenu(R.menu.source_login)
        binding.toolBar.menu.applyTint(requireContext())
    }

    private fun parseJsCode(loginUiStr: String): String {
        return when {
            loginUiStr.startsWith("@js:") -> loginUiStr.substring(4)
            loginUiStr.startsWith("<js>") -> {
                val endIndex = loginUiStr.lastIndexOf("<")
                if (endIndex > 4) {
                    loginUiStr.substring(4, endIndex)
                } else {
                    loginUiStr
                }
            }
            else -> loginUiStr
        }
    }

    private fun evalUiJs(jsStr: String): String? {
        val source = viewModel.source ?: return null
        val loginJS = source.getLoginJs() ?: ""
        val loginData = viewModel.loginInfo 

        return try {
            source.evalJS("$loginJS\n$jsStr") {
                put("java", sourceLoginJsExtensions)
                put("result", loginData)
                put("book", viewModel.book)
                put("chapter", viewModel.chapter)
            }.toString()
        } catch(e: Exception) {
            AppLog.put(source.getTag() + " loginUi error", e)
            null
        }
    }

    @SuppressLint("SetTextI18n")
    private fun rowUiBuilder(source: BaseSource, rowUis: List<RowUi>?) {
        rowUis?.forEachIndexed { index, rowUi ->
            rowUiName.add(rowUi.name)
            when (rowUi.type) {
                Type.text -> ItemSourceEditBinding.inflate(
                    layoutInflater,
                    binding.flexbox,
                    false
                ).let {
                    binding.flexbox.addView(it.root)
                    it.root.id = index + 1000
                    it.textInputLayout.hint = rowUi.name
                    it.editText.setText(viewModel.loginInfo[rowUi.name] ?: rowUi.default ?: "")
                }

                Type.password -> ItemSourceEditBinding.inflate(
                    layoutInflater,
                    binding.flexbox,
                    false
                ).let {
                    binding.flexbox.addView(it.root)
                    it.root.id = index + 1000
                    it.textInputLayout.hint = rowUi.name
                    it.editText.inputType =
                        InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT
                    it.editText.setText(viewModel.loginInfo[rowUi.name] ?: rowUi.default ?: "")
                }

                Type.button -> ItemFilletTextBinding.inflate(
                    layoutInflater,
                    binding.flexbox,
                    false
                ).let {
                    binding.flexbox.addView(it.root)
                    rowUi.style().apply(it.root)
                    it.root.id = index + 1000
                    it.textView.text = rowUi.name
                    it.textView.setPadding(16.dpToPx())
                    it.root.onClick {
                        handleButtonClick(source, rowUi)
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
                    login(source, getLoginData(rowUis))
                }
                R.id.menu_show_login_header -> alert {
                    setTitle(R.string.login_header)
                    source.getLoginHeader()?.let { loginHeader ->
                        setMessage(loginHeader)
                        positiveButton(R.string.copy_text) {
                            appCtx.sendToClip(loginHeader)
                        }
                    }
                }
                R.id.menu_del_login_header -> source.removeLoginHeader()
                R.id.menu_del_login_info -> source.removeLoginInfo()
                R.id.menu_log -> showDialogFragment<AppLogDialog>()
            }
            true
        }
    }

    private fun handleButtonClick(source: BaseSource, rowUi: RowUi) {
        lifecycleScope.launch(IO) {
            if (rowUi.action.isAbsUrl()) {
                context?.openUrl(rowUi.action!!)
            } else if (rowUi.action != null) {
                val loginJS = source.getLoginJs() ?: return@launch
                val actionJs = rowUi.action
                kotlin.runCatching {
                    source.evalJS("$loginJS\n$actionJs") {
                        put("java", sourceLoginJsExtensions)
                        put("result", getLoginData(rowUis))
                        put("book", viewModel.book)
                        put("chapter", viewModel.chapter)
                    }
                }.onFailure { e ->
                    AppLog.put("LoginUI Button ${rowUi.name} JavaScript error", e)
                }
            }
        }
    }

    private fun getLoginData(rowUis: List<RowUi>?): MutableMap<String, String> {
        return runBlocking(Main) {
            val loginData = viewModel.loginInfo
            rowUis?.forEachIndexed { index, rowUi ->
                when (rowUi.type) {
                    Type.text, Type.password -> {
                        val rowView = binding.root.findViewById<View>(index + 1000)
                        if (rowView != null) {
                            val text = ItemSourceEditBinding.bind(rowView).editText.text?.toString()
                            loginData[rowUi.name] = text ?: rowUi.default ?: ""
                        }
                    }
                }
            }
            loginData
        }
    }

    private fun login(source: BaseSource, loginData: MutableMap<String, String>) {
        lifecycleScope.launch(IO) {
            if (loginData.isEmpty()) {
                source.removeLoginInfo()
                withContext(Main) {
                    dismiss()
                }
            } else if (source.putLoginInfo(GSON.toJson(loginData))) {
                try {
                    source.login()
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