package es.zelliot.perceptron

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spannable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import es.zelliot.perceptron.databinding.ActivityTesseractBinding
import kotlinx.coroutines.*
import java.util.regex.Pattern
import kotlin.math.max

class TesseractActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTesseractBinding
    private lateinit var highlighter: TesseractHighlighter
    
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
    private var currentFileUri: Uri? = null

    private var isSearchPanelOpen = false
    private var searchMatches = listOf<IntRange>()
    private var currentMatchIndex = -1
    private var searchDebounceJob: Job? = null
    
    private var isResultExpanded = false
    private var savedMaxHeight = 0

    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val deltaX = e2.x - (e1?.x ?: 0f)
                val deltaY = e2.y - (e1?.y ?: 0f)
                
                if (Math.abs(deltaX) > Math.abs(deltaY) && Math.abs(deltaX) > 100f && Math.abs(velocityX) > 100f) {
                    if (deltaX < 0) {
                        openSearchPanel()
                        return true
                    } else if (isSearchPanelOpen) {
                        closeSearchPanel()
                        return true
                    }
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private val openFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            currentFileUri = uri
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
            }
            loadFileContent(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val prefs = getSharedPreferences("perceptron_prefs", Context.MODE_PRIVATE)
        val screenshotsEnabled = prefs.getBoolean("screenshot_enabled", false)
        if (screenshotsEnabled) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        binding = ActivityTesseractBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSyntaxHighlighting()
        setupButtons()
        setupOverlays()
        checkIntentForShortcut()
        
        setupSearchAndScroll()
        
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { 
                if (isSearchPanelOpen) {
                    closeSearchPanel()
                } else {
                    showExitConfirmationDialog() 
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    private fun setupSyntaxHighlighting() {
        highlighter = TesseractHighlighter(binding.etScript, lifecycle)
        binding.etScript.addTextChangedListener(highlighter)
    }

    private fun setupButtons() {
        binding.btnClear.setOnClickListener { showClearConfirmationDialog() }
        binding.btnExecute.setOnClickListener { executeScript(binding.etScript.text.toString()) }
        binding.btnStop.setOnClickListener {
            activityScope.coroutineContext[Job]?.cancelChildren()
            showToast(getString(R.string.toast_stopped))
        }
        binding.btnOpen.setOnClickListener {
            openFileLauncher.launch(arrayOf("*/*", "text/plain", "application/octet-stream"))
        }
        binding.btnShortcut.setOnClickListener { showShortcutDialog() }
        binding.btnExit.setOnClickListener { showExitConfirmationDialog() }
    }
    
    private fun showExitConfirmationDialog() {
        val darkContext = ContextThemeWrapper(this, R.style.DarkDialogTheme)
        AlertDialog.Builder(darkContext)
            .setTitle(getString(R.string.dialog_exit_title))
            .setMessage(getString(R.string.dialog_exit_message))
            .setPositiveButton(getString(R.string.dialog_yes)) { _, _ -> 
                finishAffinity()
                kotlin.system.exitProcess(0)
            }
            .setNegativeButton(getString(R.string.dialog_no), null)
            .show()
    }
    
    private fun showClearConfirmationDialog() {
        if (binding.etScript.text.isNullOrEmpty() && currentFileUri == null) return
        
        val darkContext = ContextThemeWrapper(this, R.style.DarkDialogTheme)
        AlertDialog.Builder(darkContext)
            .setTitle("Очиститт")
            .setMessage("Вы точно хотите очистить поле ввода?")
            .setPositiveButton(getString(R.string.dialog_yes)) { _, _ -> 
                binding.etScript.text.clear()
                currentFileUri = null
                showToast(getString(R.string.toast_cleared))
            }
            .setNegativeButton(getString(R.string.dialog_no), null)
            .show()
    }

    private fun setupOverlays() {
        binding.btnCloseResult.setOnClickListener { hideResult() }
        binding.dimView.setOnClickListener { hideResult() }
        
        binding.btnCopyResult.setOnClickListener {
            val text = binding.tvResultContent.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("result", text))
            showToast("Скопировано")
        }
        
        binding.btnExpandResult.setOnClickListener {
            if (isResultExpanded) {
                collapseResult()
            } else {
                expandResult()
            }
        }
    }

    private fun showResult(text: String) {
        val spannableText = applyResultSyntax(text)
        binding.tvResultContent.text = spannableText
        binding.tvResultContent.scrollTo(0, 0)
        
        if (isResultExpanded) {
            collapseResult()
        }
        
        binding.dimView.visibility = View.VISIBLE
        binding.dimView.alpha = 0f
        binding.dimView.animate().alpha(1f).setDuration(200).start()

        binding.overlayResult.visibility = View.VISIBLE
        binding.overlayResult.alpha = 0f
        binding.overlayResult.scaleY = 0.95f
        binding.overlayResult.scaleX = 0.95f
        binding.overlayResult.animate().alpha(1f).scaleY(1f).scaleX(1f).setDuration(200).start()
    }

    private fun applyResultSyntax(text: String): Spannable {
        val spannable = android.text.SpannableString(text)
        
        val cyanColor = Color.parseColor("#00E5FF")
        spannable.setSpan(ForegroundColorSpan(cyanColor), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        val greenColor = Color.parseColor("#A8FF60")
        
        val successPatterns = listOf(
            Regex("\\bSuccess\\b", RegexOption.IGNORE_CASE),
            Regex("\\bOK\\b", RegexOption.IGNORE_CASE),
            Regex("\\btrue\\b", RegexOption.IGNORE_CASE),
            Regex("\\bCompleted\\b", RegexOption.IGNORE_CASE),
            Regex("\\bDone\\b", RegexOption.IGNORE_CASE),
            Regex("\\bPassed\\b", RegexOption.IGNORE_CASE),
            Regex("\\b✓\\b"),
            Regex("\\b✔\\b"),
            Regex("\\b\\+\\d+(?:\\.\\d+)?\\b"),
            Regex("\\b\\d+(?:\\.\\d+)?\\s*(?:ms|s|sec|seconds|milliseconds)\\b", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in successPatterns) {
            for (match in pattern.findAll(text)) {
                spannable.setSpan(
                    ForegroundColorSpan(greenColor),
                    match.range.first,
                    match.range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        
        return spannable
    }

    private fun hideResult() {
        if (isResultExpanded) {
            collapseResult()
        }
        
        binding.dimView.animate().alpha(0f).setDuration(150).withEndAction { binding.dimView.visibility = View.GONE }.start()
        binding.overlayResult.animate().alpha(0f).scaleY(0.95f).scaleX(0.95f).setDuration(150).withEndAction {
            binding.overlayResult.visibility = View.GONE
            binding.overlayResult.alpha = 1f
            binding.overlayResult.scaleY = 1f
            binding.overlayResult.scaleX = 1f
        }.start()
    }
    
    private fun expandResult() {
        savedMaxHeight = binding.tvResultContent.maxHeight
        
        binding.resultContainer.setPadding(0, 0, 0, 0)
        
        val params = binding.overlayResult.layoutParams as FrameLayout.LayoutParams
        params.width = FrameLayout.LayoutParams.MATCH_PARENT
        params.height = FrameLayout.LayoutParams.MATCH_PARENT
        params.gravity = android.view.Gravity.NO_GRAVITY
        binding.overlayResult.layoutParams = params
        
        binding.tvResultContent.maxHeight = Int.MAX_VALUE
        
        binding.btnExpandResult.text = "Уменьшить"
        isResultExpanded = true
    }
    
    private fun collapseResult() {
        val paddingPx = (24 * resources.displayMetrics.density).toInt()
        binding.resultContainer.setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        
        val params = binding.overlayResult.layoutParams as FrameLayout.LayoutParams
        params.width = FrameLayout.LayoutParams.MATCH_PARENT
        params.height = FrameLayout.LayoutParams.WRAP_CONTENT
        params.gravity = android.view.Gravity.CENTER
        binding.overlayResult.layoutParams = params
        
        binding.tvResultContent.maxHeight = savedMaxHeight
        
        binding.btnExpandResult.text = "Расширить"
        isResultExpanded = false
    }

    data class ConstantDef(val name: String, val defaultValue: Double, val matchRange: IntRange, val type: String)

    private fun extractConstants(script: String): List<ConstantDef> {
        val constants = mutableListOf<ConstantDef>()
        val regex = Regex("""(const|val|var)\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)""", RegexOption.IGNORE_CASE)
        for (match in regex.findAll(script)) {
            val type = match.groupValues[1].lowercase()
            val name = match.groupValues[2]
            val value = match.groupValues[3].toDoubleOrNull() ?: 0.0
            constants.add(ConstantDef(name, value, match.range, type))
        }
        return constants
    }

    private fun loadFileContent(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val content = inputStream.bufferedReader().use { it.readText() }
            currentFileUri = uri
            
            val constants = extractConstants(content)
            if (constants.isNotEmpty()) {
                showConstantsDialog(constants, content)
            } else {
                binding.etScript.setText(content)
                showToast(getString(R.string.toast_file_loaded))
            }
        } catch (e: Exception) {
            showToast(getString(R.string.toast_file_error, e.message ?: "Unknown"))
        }
    }

    private fun showConstantsDialog(constants: List<ConstantDef>, originalScript: String) {
        val darkContext = ContextThemeWrapper(this, R.style.DarkDialogTheme)
        val builder = AlertDialog.Builder(darkContext).setTitle(getString(R.string.dialog_params_title))
        
        val layout = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
            setBackgroundColor(Color.parseColor("#0A0A0A"))
        }
        val editTexts = mutableMapOf<String, EditText>()

        val sortedConstants = constants.sortedBy { if (it.type == "const") 0 else 1 }
        val hasConst = sortedConstants.any { it.type == "const" }
        val hasValVar = sortedConstants.any { it.type != "const" }
        var addedDivider = false

        for (const in sortedConstants) {
            if (hasConst && hasValVar && const.type != "const" && !addedDivider) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (1 * resources.displayMetrics.density).toInt()
                    ).apply {
                        setMargins(0, 30, 0, 30)
                    }
                    setBackgroundColor(Color.parseColor("#2C2F33"))
                }
                layout.addView(divider)
                addedDivider = true
            }

            val nameColor = if (const.type == "const") {
                Color.parseColor("#00E5FF")
            } else {
                Color.parseColor("#C792EA")
            }
            
            val tintColor = if (const.type == "const") {
                Color.parseColor("#00E5FF")
            } else {
                Color.parseColor("#C792EA")
            }

            val tv = TextView(this).apply {
                text = const.name
                setTextColor(nameColor)
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val et = EditText(this).apply {
                setText(const.defaultValue.toString())
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                setTextColor(Color.parseColor("#00FFFF"))
                setHintTextColor(Color.GRAY)
                backgroundTintList = android.content.res.ColorStateList.valueOf(tintColor)
                setLongClickable(false)
                setTextIsSelectable(false)
                val actionModeCallback = object : ActionMode.Callback {
                    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
                    override fun onDestroyActionMode(mode: ActionMode?) {}
                }
                setCustomSelectionActionModeCallback(actionModeCallback)
                setCustomInsertionActionModeCallback(actionModeCallback)
            }
            editTexts[const.name] = et
            layout.addView(tv)
            layout.addView(et)
        }

        val scrollView = ScrollView(this).apply { 
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            addView(layout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        builder.setView(scrollView)

        builder.setPositiveButton(getString(R.string.btn_execute)) { _, _ ->
            val regex = Regex("""(const|val|var)\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)""", RegexOption.IGNORE_CASE)
            val sb = StringBuilder()
            var lastEnd = 0
            
            for (match in regex.findAll(originalScript)) {
                sb.append(originalScript, lastEnd, match.range.first)
                val originalType = match.groupValues[1]
                val name = match.groupValues[2]
                val newValue = editTexts[name]?.text.toString().toDoubleOrNull() ?: match.groupValues[3]
                sb.append("$originalType $name = $newValue")
                lastEnd = match.range.last + 1
            }
            sb.append(originalScript, lastEnd, originalScript.length)

            binding.etScript.setText(sb.toString())
            executeScript(sb.toString())
        }
        builder.setNegativeButton(getString(R.string.dialog_cancel)) { _, _ ->
            binding.etScript.setText(originalScript)
        }
        
        val dialog = builder.show()
        
        // Программно разделяем кнопки "Выполнить" и "Отмена"
        try {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            
            // Добавляем левый отступ к положительной кнопке (Выполнить)
            val params = positiveButton.layoutParams as? android.widget.LinearLayout.LayoutParams
            params?.setMargins(24, 0, 0, 0) // 24px отступ слева
            positiveButton.layoutParams = params
            
            // Усиливаем контраст цветов
            positiveButton.setTextColor(Color.parseColor("#C792EA")) // Фиолетовый
            negativeButton.setTextColor(Color.parseColor("#7A6652")) // Коричневый
        } catch (e: Exception) {
            // Игнорируем ошибки стилизации
        }
    }

    private fun showShortcutDialog() {
        if (currentFileUri == null) {
            showToast(getString(R.string.toast_open_file_first))
            return
        }
        val darkContext = ContextThemeWrapper(this, R.style.DarkDialogTheme)
        val builder = AlertDialog.Builder(darkContext).setTitle(getString(R.string.dialog_shortcut_title))
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 20) }
        val etTitle = EditText(this).apply {
            hint = getString(R.string.hint_shortcut_name); setTextColor(Color.parseColor("#00E676")); setHintTextColor(Color.GRAY)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#C792EA"))
            setLongClickable(false); setTextIsSelectable(false)
            val actionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
                override fun onDestroyActionMode(mode: ActionMode?) {}
            }
            setCustomSelectionActionModeCallback(actionModeCallback)
            setCustomInsertionActionModeCallback(actionModeCallback)
        }
        layout.addView(etTitle)
        builder.setView(layout)

        builder.setPositiveButton(getString(R.string.btn_create)) { _, _ ->
            val title = etTitle.text.toString().trim()
            if (title.isNotEmpty()) createShortcut(title) else showToast(getString(R.string.toast_enter_shortcut_name))
        }
        builder.setNegativeButton(getString(R.string.dialog_cancel), null)
        builder.show()
    }

    private fun createShortcut(title: String) {
        val intent = Intent(this, TesseractActivity::class.java).apply {
            action = "es.zelliot.perceptron.ACTION_RUN_SHORTCUT"
            putExtra("SCRIPT_URI", currentFileUri.toString())
        }
        val shortcut = ShortcutInfoCompat.Builder(this, "perceptron_script_${System.currentTimeMillis()}")
            .setShortLabel(title).setLongLabel(title)
            .setIcon(IconCompat.createWithResource(this, android.R.drawable.ic_menu_edit))
            .setIntent(intent).build()
        ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
        showToast(getString(R.string.toast_shortcut_created, title))
    }

    private fun executeScript(script: String) {
        activityScope.coroutineContext[Job]?.cancelChildren()
        
        val trimmedScript = script.trim()
        if (trimmedScript == "PERCEPTRON_SCREENSHOT_ENABLE") {
            val prefs = getSharedPreferences("perceptron_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("screenshot_enabled", true).apply()
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            showResult("Скриншоты разрешены")
            return
        } else if (trimmedScript == "PERCEPTRON_SCREENSHOT_DISABLE") {
            val prefs = getSharedPreferences("perceptron_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("screenshot_enabled", false).apply()
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            showResult("Скриншоты запрещены")
            return
        }
        
        activityScope.launch {
            try {
                val systemResult = try {
                    withContext(Dispatchers.Default) {
                        withTimeout(3000) { TesseractEngine2.evaluateSystemCommand(this@TesseractActivity, script) }
                    }
                } catch (e: TimeoutCancellationException) { getString(R.string.error_timeout_system) }
                
                if (systemResult.isNotEmpty()) {
                    if (systemResult == "__TESSERACT_CLEAR__") {
                        binding.etScript.text.clear()
                        showToast(getString(R.string.toast_screen_cleared))
                    } else { showResult(systemResult) }
                    return@launch
                }

                val isEngine4Script = Regex("\\b(matrix|complex|gcd|lcm|factorial|comb|perm|is_finite|is_integer|is_close|rational|differentiate|integrate|simplify|solve|factor|expand|mempty|mappend|fmap|ap|bind|pure|transpose|det|inverse|dot|norm|cross|identity|zeros|ones|hypot|atan2|degrees|radians|sign|clamp|arg|conj|real|imag)\\b|\\*\\*").containsMatchIn(script)

                val result = try {
                    withContext(Dispatchers.Default) {
                        withTimeout(3000) {
                            if (isEngine4Script) {
                                TesseractEngine4.evaluate(this@TesseractActivity, script, emptyMap())
                            } else {
                                TesseractEngine3.evaluate(this@TesseractActivity, script, emptyMap())
                            }
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    getString(R.string.error_timeout_script)
                }

                if (result.startsWith("__TESSERACT_EXIT__:")) {
                    val firstLine = result.substringBefore("\n")
                    val actualOutput = result.substringAfter("\n", "").trim()
                    val delayStr = firstLine.removePrefix("__TESSERACT_EXIT__:")
                    val delayMs = (delayStr.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                    
                    if (actualOutput.isNotEmpty() && actualOutput != "void") showResult(actualOutput) 
                    else showResult(getString(R.string.toast_script_executed_exit))
                    
                    showToast(getString(R.string.toast_exiting_in_ms, delayMs))
                    delay(delayMs)
                    finishAffinity()
                    kotlin.system.exitProcess(0)
                } else {
                    showResult(result)
                }

            } catch (e: TesseractOpenActCommand3) {
                val target = e.packageName
                try {
                    val intent: Intent? = if (target.contains("/")) {
                        val parts = target.split("/", limit = 2)
                        Intent().setClassName(parts[0], parts[1])
                    } else {
                        packageManager.getLaunchIntentForPackage(target)
                    }
                    if (intent != null) {
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(intent)
                        showResult(getString(R.string.toast_transition_success, target))
                    } else {
                        showResult(getString(R.string.error_open_target_tips, target))
                    }
                } catch (ex: Exception) {
                    showResult(getString(R.string.error_open_target_exception, target, ex.message ?: "Unknown"))
                }
            } catch (e: TesseractOpenActCommand4) {
                val target = e.packageName
                try {
                    val intent: Intent? = if (target.contains("/")) {
                        val parts = target.split("/", limit = 2)
                        Intent().setClassName(parts[0], parts[1])
                    } else {
                        packageManager.getLaunchIntentForPackage(target)
                    }
                    if (intent != null) {
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(intent)
                        showResult(getString(R.string.toast_transition_success, target))
                    } else {
                        showResult(getString(R.string.error_open_target_tips, target))
                    }
                } catch (ex: Exception) {
                    showResult(getString(R.string.error_open_target_exception, target, ex.message ?: "Unknown"))
                }
            } catch (e: CancellationException) {
            } catch (e: Exception) {
                showResult(getString(R.string.error_generic, e.message ?: "Unknown"))
            }
        }
    }

    private fun checkIntentForShortcut() {
        if (intent.action == "es.zelliot.perceptron.ACTION_RUN_SHORTCUT") {
            val uriString = intent.getStringExtra("SCRIPT_URI")
            if (uriString != null) {
                currentFileUri = Uri.parse(uriString)
                loadFileContent(currentFileUri!!)
            }
        } else if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            currentFileUri = intent.data
            loadFileContent(currentFileUri!!)
        }
    }

    private fun showToast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() {
        super.onDestroy()
        if (::highlighter.isInitialized) {
            binding.etScript.removeTextChangedListener(highlighter)
            highlighter.cleanup()
        }
        activityScope.cancel()
    }

    private fun setupSearchAndScroll() {
        setupSearchListeners()
        setupQuickScroll()
    }

    private fun openSearchPanel() {
        isSearchPanelOpen = true
        binding.searchPanel.visibility = View.VISIBLE
        binding.searchPanel.alpha = 0f
        binding.searchPanel.animate().alpha(1f).setDuration(200).start()
        binding.etSearch.requestFocus()
        performSearch()
    }

    private fun closeSearchPanel() {
        isSearchPanelOpen = false
        binding.searchPanel.animate().alpha(0f).setDuration(150).withEndAction {
            binding.searchPanel.visibility = View.GONE
            binding.etSearch.clearFocus()
            hideKeyboard()
        }.start()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    }

    private fun setupSearchListeners() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchDebounceJob?.cancel()
                searchDebounceJob = activityScope.launch {
                    delay(150)
                    performSearch()
                }
            }
        })

        binding.btnSearchPrev.setOnClickListener { goToMatch(-1) }
        binding.btnSearchNext.setOnClickListener { goToMatch(1) }
        binding.btnReplaceOne.setOnClickListener { replaceCurrentMatch() }
        binding.btnReplaceAll.setOnClickListener { showReplaceAllDialog() }
    }

    private fun performSearch() {
        val query = binding.etSearch.text.toString()
        val text = binding.etScript.text?.toString() ?: ""

        if (query.isEmpty()) {
            searchMatches = emptyList()
            currentMatchIndex = -1
            return
        }

        try {
            val escaped = Regex.escape(query)
            val regex = Regex(escaped, RegexOption.IGNORE_CASE)
            searchMatches = regex.findAll(text).map { it.range }.toList()
            currentMatchIndex = if (searchMatches.isNotEmpty()) 0 else -1

            if (currentMatchIndex >= 0) {
                selectMatchAt(currentMatchIndex)
            }
        } catch (e: Exception) {
            searchMatches = emptyList()
            currentMatchIndex = -1
        }
    }

    private fun selectMatchAt(index: Int) {
        if (index < 0 || index >= searchMatches.size) return
        val range = searchMatches[index]
        val textLen = binding.etScript.text?.length ?: 0
        val safeStart = range.first.coerceIn(0, textLen)
        val safeEnd = (range.last + 1).coerceIn(0, textLen)
        
        // ИСПРАВЛЕНИЕ: Убран вызов requestFocus(), чтобы фокус ввода оставался 
        // в поле поиска (etSearch). Это позволяет пользователю продолжать 
        // вводить поисковый запрос, а выделение в редакторе обновится автоматически.
        binding.etScript.setSelection(safeStart, safeEnd)
        revealSelection(safeStart)
    }

    private fun revealSelection(selectionStart: Int) {
        binding.etScript.post {
            val layout = binding.etScript.layout ?: return@post
            val line = layout.getLineForOffset(selectionStart)
            val y = layout.getLineTop(line)
            binding.etScript.scrollTo(0, y)
        }
    }

    private fun goToMatch(direction: Int) {
        if (searchMatches.isEmpty()) {
            showToast("Нет совпадений")
            return
        }
        currentMatchIndex = if (direction > 0) {
            (currentMatchIndex + 1) % searchMatches.size
        } else {
            if (currentMatchIndex - 1 < 0) searchMatches.size - 1 else currentMatchIndex - 1
        }
        selectMatchAt(currentMatchIndex)
        showToast("Совпадение ${currentMatchIndex + 1} из ${searchMatches.size}")
    }

    private fun replaceCurrentMatch() {
        if (currentMatchIndex < 0 || searchMatches.isEmpty()) {
            showToast("Нет совпадений для замены")
            return
        }
        val query = binding.etSearch.text.toString()
        val replaceText = binding.etReplace.text.toString()
        if (query.isEmpty()) {
            showToast("Поле поиска пустое")
            return
        }
        
        val range = searchMatches[currentMatchIndex]
        val editable = binding.etScript.text ?: return
        editable.replace(range.first, range.last + 1, replaceText)
        
        performSearch()
        showToast("Заменено")
    }

    private fun showReplaceAllDialog() {
        val query = binding.etSearch.text.toString()
        val replaceText = binding.etReplace.text.toString()
        if (query.isEmpty()) {
            showToast("Поле поиска пустое")
            return
        }

        val darkContext = ContextThemeWrapper(this, R.style.DarkDialogTheme)
        AlertDialog.Builder(darkContext)
            .setTitle("Заменить всё?")
            .setMessage("Заменить все вхождения \"$query\" на \"$replaceText\"?")
            .setPositiveButton("ДА") { _, _ ->
                try {
                    val text = binding.etScript.text?.toString() ?: ""
                    val escaped = Regex.escape(query)
                    val regex = Regex(escaped, RegexOption.IGNORE_CASE)
                    val newText = regex.replace(text, replaceText)
                    
                    binding.etScript.setText(newText)
                    binding.etScript.setSelection(0)
                    searchMatches = emptyList()
                    currentMatchIndex = -1
                    showToast("Все совпадения заменены")
                } catch (e: Exception) {
                    showToast("Ошибка замены: ${e.message}")
                }
            }
            .setNegativeButton("ОТМЕНА", null)
            .show()
    }

    private fun setupQuickScroll() {
        binding.root.setOnTouchListener { v, ev ->
            try {
                val edgeWidthPx = dpToPx(24)
                val x = ev.x
                val y = ev.y
                
                if (x <= edgeWidthPx) {
                    if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) {
                        val layout = binding.etScript.layout ?: return@setOnTouchListener true
                        val ratio = (y / v.height).coerceIn(0f, 1f)
                        val targetLine = ((layout.lineCount - 1) * ratio).toInt()
                            .coerceIn(0, max(0, layout.lineCount - 1))
                        val offset = layout.getLineStart(targetLine)
                        
                        binding.etScript.requestFocus()
                        binding.etScript.setSelection(offset)
                        val top = layout.getLineTop(targetLine)
                        binding.etScript.scrollTo(0, top)
                    }
                    return@setOnTouchListener true
                }
            } catch (_: Exception) { }
            false
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    private class TesseractHighlighter(
        private val editText: EditText,
        private val lifecycle: androidx.lifecycle.Lifecycle
    ) : TextWatcher {
        private val colorKeyword = Color.parseColor("#C792EA")
        private val colorString = Color.parseColor("#C3E88D")
        private val colorComment = Color.parseColor("#546E7A")
        private val colorNumber = Color.parseColor("#F78C6C")
        private val colorFunction = Color.parseColor("#82AAFF")
        private val colorOperator = Color.parseColor("#89DDFF")

        private val stringPattern = Pattern.compile("(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|`(?:[^`\\\\]|\\\\.)*`)")
        private val commentPattern = Pattern.compile("(//.*|/\\*[\\s\\S]*?\\*/|#.*)")
        private val keywordPattern = Pattern.compile("\\b(if|else|elif|for|while|do|return|break|continue|try|catch|finally|throw|val|var|const|fn|function|class|interface|object|package|import|from|as|in|is|not|and|or|true|false|null|void|exit|assert|to)\\b")
        private val functionPattern = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(")
        private val numberPattern = Pattern.compile("\\b(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b")
        private val operatorPattern = Pattern.compile("(==|!=|<=|>=|&&|\\|\\||\\+\\+|--|<<|>>|\\+=|-=|\\*=|/=|%=|\\.)")

        private var debounceJob: Job? = null
        private val debounceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private val debounceDelay = 300L
        @Volatile private var isActive = true

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable?) {
            if (!isActive || s == null) return
            debounceJob?.cancel()
            debounceJob = debounceScope.launch {
                delay(debounceDelay)
                if (!isActive) return@launch
                applySyntaxHighlighting(s)
            }
        }
        
        private fun applySyntaxHighlighting(editable: Editable) {
            try {
                val text = editable.toString()
                if (text.length > 50000) { applyMinimalHighlighting(editable, text); return }
                
                removeOldSpans(editable)
                applyPatternSafe(editable, text, stringPattern, colorString)
                applyPatternSafe(editable, text, commentPattern, colorComment)
                applyPatternSafe(editable, text, operatorPattern, colorOperator)
                applyPatternSafe(editable, text, keywordPattern, colorKeyword)
                applyPatternSafe(editable, text, functionPattern, colorFunction)
                applyPatternSafe(editable, text, numberPattern, colorNumber)
            } catch (e: Exception) {
                android.util.Log.w("TesseractHighlighter", "Error applying syntax highlighting", e)
            }
        }
        
        private fun applyMinimalHighlighting(editable: Editable, text: String) {
            removeOldSpans(editable)
            applyPatternSafe(editable, text, stringPattern, colorString)
            applyPatternSafe(editable, text, commentPattern, colorComment)
        }
        
        private fun removeOldSpans(editable: Editable) {
            val oldSpans = editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
            for (span in oldSpans) {
                try { editable.removeSpan(span) } catch (e: Exception) {}
            }
        }

        private fun applyPatternSafe(editable: Editable, text: String, pattern: Pattern, color: Int) {
            try {
                val matcher = pattern.matcher(text)
                while (matcher.find()) {
                    if (!isActive) return
                    val start = matcher.start()
                    val end = matcher.end()
                    if (start < 0 || end > text.length || start >= end) continue
                    
                    if (!isInsideStringOrComment(text, start, end)) {
                        try { 
                            editable.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) 
                        } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {}
        }
        
        private fun isInsideStringOrComment(text: String, start: Int, end: Int): Boolean {
            try {
                var inDoubleQuote = false
                var inSingleQuote = false
                var inBacktick = false
                var blockCommentDepth = 0
                var i = 0
                
                while (i < start) {
                    if (text[i] == '\\') {
                        i += 2
                        continue
                    }
                    
                    val char = text[i]
                    when (char) {
                        '"' -> if (!inSingleQuote && !inBacktick && blockCommentDepth == 0) inDoubleQuote = !inDoubleQuote
                        '\'' -> if (!inDoubleQuote && !inBacktick && blockCommentDepth == 0) inSingleQuote = !inSingleQuote
                        '`' -> if (!inDoubleQuote && !inSingleQuote && blockCommentDepth == 0) inBacktick = !inBacktick
                    }
                    
                    if (blockCommentDepth == 0) {
                        if (i < text.length - 1 && text[i] == '/' && text[i + 1] == '*') { 
                            blockCommentDepth++
                            i += 2
                            continue 
                        }
                    } else {
                        if (i < text.length - 1 && text[i] == '*' && text[i + 1] == '/') { 
                            blockCommentDepth--
                            i += 2
                            continue 
                        }
                    }
                    i++
                }
                
                if (inDoubleQuote || inSingleQuote || inBacktick || blockCommentDepth > 0) return true
                
                val lineStart = text.lastIndexOf('\n', start - 1) + 1
                val linePrefix = text.substring(lineStart, start)
                if (linePrefix.contains("//") || linePrefix.contains("#")) return true
                
                return false
            } catch (e: Exception) { 
                return false 
            }
        }
        
        fun cleanup() {
            isActive = false
            debounceJob?.cancel()
            debounceScope.cancel()
        }
    }
}
