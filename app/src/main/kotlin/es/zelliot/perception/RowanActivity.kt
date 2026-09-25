package es.zelliot.perceptron

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
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
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import es.zelliot.perceptron.databinding.ActivityRowanBinding
import es.zelliot.perceptron.engine.RowanExecutionManager
import kotlinx.coroutines.*
import java.util.regex.Pattern
import kotlin.math.max

class RowanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRowanBinding
    private lateinit var highlighter: RowanHighlighter
    
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
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } 
            catch (e: SecurityException) { }
            loadFileContent(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Используем существующие настройки perceptron для простоты
        val prefs = getSharedPreferences("perceptron_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("screenshot_enabled", false)) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        binding = ActivityRowanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSyntaxHighlighting()
        setupButtons()
        setupOverlays()
        checkIntentForShortcut()
        setupSearchAndScroll()
        
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { 
                if (isSearchPanelOpen) closeSearchPanel() else showExitConfirmationDialog() 
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    private fun setupSyntaxHighlighting() {
        highlighter = RowanHighlighter(binding.etScript, lifecycle)
        binding.etScript.addTextChangedListener(highlighter)
    }

    private fun setupButtons() {
        binding.btnClear.setOnClickListener { showClearConfirmationDialog() }
        binding.btnExecute.setOnClickListener { executeScript(binding.etScript.text.toString()) }
        binding.btnStop.setOnClickListener {
            activityScope.coroutineContext[Job]?.cancelChildren()
            showToast("Выполнение остановлено")
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
            .setTitle("Выход")
            .setMessage("Завершить работу?")
            .setPositiveButton("ДА") { _, _ -> finishAffinity(); kotlin.system.exitProcess(0) }
            .setNegativeButton("НЕТ", null)
            .show()
    }
    
    private fun showClearConfirmationDialog() {
        if (binding.etScript.text.isNullOrEmpty() && currentFileUri == null) return
        val darkContext = ContextThemeWrapper(this, R.style.DarkDialogTheme)
        AlertDialog.Builder(darkContext)
            .setTitle("Очистить")
            .setMessage("Вы точно хотите очистить поле ввода?")
            .setPositiveButton("ДА") { _, _ -> binding.etScript.text.clear(); currentFileUri = null; showToast("Очищено") }
            .setNegativeButton("НЕТ", null)
            .show()
    }

    private fun setupOverlays() {
        binding.btnCloseResult.setOnClickListener { hideResult() }
        binding.dimView.setOnClickListener { hideResult() }
        binding.btnCopyResult.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("perceptron_result", binding.tvResultContent.text.toString()))
            showToast("Скопировано")
        }
        binding.btnExpandResult.setOnClickListener {
            if (isResultExpanded) collapseResult() else expandResult()
        }
    }

    private fun showResult(text: String) {
        val spannableText = applyResultSyntax(text)
        binding.tvResultContent.text = spannableText
        binding.tvResultContent.scrollTo(0, 0)
        if (isResultExpanded) collapseResult()
        
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
        spannable.setSpan(ForegroundColorSpan(Color.parseColor("#00E5FF")), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        val greenColor = Color.parseColor("#A8FF60")
        val successPatterns = listOf(
            Regex("\\bSuccess\\b", RegexOption.IGNORE_CASE), Regex("\\bOK\\b", RegexOption.IGNORE_CASE),
            Regex("\\btrue\\b", RegexOption.IGNORE_CASE), Regex("\\bCompleted\\b", RegexOption.IGNORE_CASE),
            Regex("\\b\\+\\d+(?:\\.\\d+)?\\b"), Regex("\\b\\d+(?:\\.\\d+)?\\s*(?:ms|s|sec)\\b", RegexOption.IGNORE_CASE)
        )
        for (pattern in successPatterns) {
            for (match in pattern.findAll(text)) {
                spannable.setSpan(ForegroundColorSpan(greenColor), match.range.first, match.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return spannable
    }

    private fun hideResult() {
        if (isResultExpanded) collapseResult()
        binding.dimView.animate().alpha(0f).setDuration(150).withEndAction { binding.dimView.visibility = View.GONE }.start()
        binding.overlayResult.animate().alpha(0f).scaleY(0.95f).scaleX(0.95f).setDuration(150).withEndAction {
            binding.overlayResult.visibility = View.GONE
            binding.overlayResult.alpha = 1f; binding.overlayResult.scaleY = 1f; binding.overlayResult.scaleX = 1f
        }.start()
    }
    
    private fun expandResult() {
        savedMaxHeight = binding.tvResultContent.maxHeight
        binding.resultContainer.setPadding(0, 0, 0, 0)
        val params = binding.overlayResult.layoutParams as FrameLayout.LayoutParams
        params.width = FrameLayout.LayoutParams.MATCH_PARENT; params.height = FrameLayout.LayoutParams.MATCH_PARENT
        params.gravity = android.view.Gravity.NO_GRAVITY
        binding.overlayResult.layoutParams = params
        binding.tvResultContent.maxHeight = Int.MAX_VALUE
        binding.btnExpandResult.text = "Уменьшить"; isResultExpanded = true
    }
    
    private fun collapseResult() {
        val paddingPx = (24 * resources.displayMetrics.density).toInt()
        binding.resultContainer.setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        val params = binding.overlayResult.layoutParams as FrameLayout.LayoutParams
        params.width = FrameLayout.LayoutParams.MATCH_PARENT; params.height = FrameLayout.LayoutParams.WRAP_CONTENT
        params.gravity = android.view.Gravity.CENTER
        binding.overlayResult.layoutParams = params
        binding.tvResultContent.maxHeight = savedMaxHeight
        binding.btnExpandResult.text = "Расширить"; isResultExpanded = false
    }

    data class ConstantDef(val name: String, val defaultValue: Double, val matchRange: IntRange, val type: String)

    private fun extractConstants(script: String): List<ConstantDef> {
        val constants = mutableListOf<ConstantDef>()
        val regex = Regex("""(const|val|var|mem)\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*(?:\[.*?\])?\s*=\s*(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)""", RegexOption.IGNORE_CASE)
        for (match in regex.findAll(script)) {
            constants.add(ConstantDef(match.groupValues[2], match.groupValues[3].toDoubleOrNull() ?: 0.0, match.range, match.groupValues[1].lowercase()))
        }
        return constants
    }

    private fun loadFileContent(uri: Uri) {
        try {
            val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
            currentFileUri = uri
            val constants = extractConstants(content)
            if (constants.isNotEmpty()) showConstantsDialog(constants, content)
            else { binding.etScript.setText(content); showToast("Файл загружен") }
        } catch (e: Exception) { showToast("Ошибка: ${e.message}") }
    }

    private fun showConstantsDialog(constants: List<ConstantDef>, originalScript: String) {
        val darkContext = ContextThemeWrapper(this, R.style.DarkDialogTheme)
        val builder = AlertDialog.Builder(darkContext).setTitle("Параметры запуска")
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 20); setBackgroundColor(Color.parseColor("#0A0A0A")) }
        val editTexts = mutableMapOf<String, EditText>()

        for (const in constants.sortedBy { if (it.type == "const") 0 else 1 }) {
            val tv = TextView(this).apply { text = const.name; setTextColor(Color.parseColor("#C792EA")); textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD) }
            val et = EditText(this).apply {
                setText(const.defaultValue.toString()); setTextColor(Color.parseColor("#00FFFF"))
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#C792EA"))
                setCustomSelectionActionModeCallback(object : ActionMode.Callback {
                    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
                    override fun onDestroyActionMode(mode: ActionMode?) {}
                })
            }
            editTexts[const.name] = et
            layout.addView(tv); layout.addView(et)
        }
        builder.setView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0A0A0A")); addView(layout) })
        builder.setPositiveButton("Выполнить") { _, _ ->
            val regex = Regex("""(const|val|var|mem)\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*(?:\[.*?\])?\s*=\s*(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)""", RegexOption.IGNORE_CASE)
            val sb = StringBuilder(); var lastEnd = 0
            for (match in regex.findAll(originalScript)) {
                sb.append(originalScript, lastEnd, match.range.first)
                val newValue = editTexts[match.groupValues[2]]?.text.toString().toDoubleOrNull() ?: match.groupValues[3]
                sb.append("${match.groupValues[1]} ${match.groupValues[2]} = $newValue")
                lastEnd = match.range.last + 1
            }
            sb.append(originalScript, lastEnd, originalScript.length)
            binding.etScript.setText(sb.toString())
            executeScript(sb.toString())
        }
        builder.setNegativeButton("Отмена") { _, _ -> binding.etScript.setText(originalScript) }
        builder.show()
    }

    private fun showShortcutDialog() {
        if (currentFileUri == null) { showToast("Сначала откройте файл"); return }
        val darkContext = ContextThemeWrapper(this, R.style.DarkDialogTheme)
        val builder = AlertDialog.Builder(darkContext).setTitle("Создать ярлык")
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 20) }
        val etTitle = EditText(this).apply {
            hint = "Имя ярлыка"; setTextColor(Color.parseColor("#00E676"))
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#C792EA"))
            setCustomSelectionActionModeCallback(object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
                override fun onDestroyActionMode(mode: ActionMode?) {}
            })
        }
        layout.addView(etTitle); builder.setView(layout)
        builder.setPositiveButton("Создать") { _, _ ->
            val title = etTitle.text.toString().trim()
            if (title.isNotEmpty()) createShortcut(title) else showToast("Введите имя")
        }
        builder.setNegativeButton("Отмена", null).show()
    }

    private fun createShortcut(title: String) {
        val intent = Intent(this, RowanActivity::class.java).apply {
            action = "es.zelliot.perceptron.ACTION_RUN_SHORTCUT"
            putExtra("SCRIPT_URI", currentFileUri.toString())
        }
        val shortcut = ShortcutInfoCompat.Builder(this, "perceptron_rowan_${System.currentTimeMillis()}")
            .setShortLabel(title).setLongLabel(title)
            .setIcon(IconCompat.createWithResource(this, android.R.drawable.ic_menu_edit))
            .setIntent(intent).build()
        ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
        showToast("Ярлык создан")
    }

    private fun executeScript(script: String) {
        activityScope.coroutineContext[Job]?.cancelChildren()
        val trimmedScript = script.trim()
        
        if (trimmedScript == "PERCEPTRON_SCREENSHOT_ENABLE") {
            getSharedPreferences("perceptron_prefs", Context.MODE_PRIVATE).edit().putBoolean("screenshot_enabled", true).apply()
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            showResult("Скриншоты разрешены"); return
        } else if (trimmedScript == "PERCEPTRON_SCREENSHOT_DISABLE") {
            getSharedPreferences("perceptron_prefs", Context.MODE_PRIVATE).edit().putBoolean("screenshot_enabled", false).apply()
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            showResult("Скриншоты запрещены"); return
        }
        
        activityScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    withTimeout(5000) { 
                        RowanExecutionManager.execute(this@RowanActivity, script) 
                    }
                }

                if (result.startsWith("__ROWAN_EXIT__:")) {
                    val delayStr = result.substringBefore("\n").removePrefix("__ROWAN_EXIT__:")
                    val actualOutput = result.substringAfter("\n", "").trim()
                    if (actualOutput.isNotEmpty() && actualOutput != "void") showResult(actualOutput)
                    showToast("Выход через ${delayStr.toLongOrNull() ?: 0} мс")
                    delay(delayStr.toLongOrNull() ?: 0)
                    finishAffinity(); kotlin.system.exitProcess(0)
                } else {
                    showResult(result)
                }

            } catch (e: TimeoutCancellationException) { showResult("Превышено время выполнения (5000 мс)") }
            catch (e: CancellationException) { }
            catch (e: Exception) { showResult("Ошибка: ${e.message ?: "Unknown"}") }
        }
    }

    private fun checkIntentForShortcut() {
        if (intent.action == "es.zelliot.perceptron.ACTION_RUN_SHORTCUT") {
            val uriString = intent.getStringExtra("SCRIPT_URI")
            if (uriString != null) { currentFileUri = Uri.parse(uriString); loadFileContent(currentFileUri!!) }
        } else if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            currentFileUri = intent.data; loadFileContent(currentFileUri!!)
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
        setupSearchListeners(); setupQuickScroll()
    }

    private fun openSearchPanel() {
        isSearchPanelOpen = true
        binding.searchPanel.visibility = View.VISIBLE
        binding.searchPanel.alpha = 0f
        binding.searchPanel.animate().alpha(1f).setDuration(200).start()
        binding.etSearch.requestFocus(); performSearch()
    }

    private fun closeSearchPanel() {
        isSearchPanelOpen = false
        binding.searchPanel.animate().alpha(0f).setDuration(150).withEndAction {
            binding.searchPanel.visibility = View.GONE
            binding.etSearch.clearFocus(); hideKeyboard()
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
                    delay(150); performSearch()
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
        if (query.isEmpty()) { searchMatches = emptyList(); currentMatchIndex = -1; return }
        try {
            val regex = Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
            searchMatches = regex.findAll(text).map { it.range }.toList()
            currentMatchIndex = if (searchMatches.isNotEmpty()) 0 else -1
            if (currentMatchIndex >= 0) selectMatchAt(currentMatchIndex)
        } catch (e: Exception) { searchMatches = emptyList(); currentMatchIndex = -1 }
    }

    private fun selectMatchAt(index: Int) {
        if (index < 0 || index >= searchMatches.size) return
        val range = searchMatches[index]
        val textLen = binding.etScript.text?.length ?: 0
        val safeStart = range.first.coerceIn(0, textLen)
        val safeEnd = (range.last + 1).coerceIn(0, textLen)
        binding.etScript.setSelection(safeStart, safeEnd)
        revealSelection(safeStart)
    }

    private fun revealSelection(selectionStart: Int) {
        binding.etScript.post {
            val layout = binding.etScript.layout ?: return@post
            val line = layout.getLineForOffset(selectionStart)
            binding.etScript.scrollTo(0, layout.getLineTop(line))
        }
    }

    private fun goToMatch(direction: Int) {
        if (searchMatches.isEmpty()) { showToast("Нет совпадений"); return }
        currentMatchIndex = if (direction > 0) (currentMatchIndex + 1) % searchMatches.size else if (currentMatchIndex - 1 < 0) searchMatches.size - 1 else currentMatchIndex - 1
        selectMatchAt(currentMatchIndex)
        showToast("Совпадение ${currentMatchIndex + 1} из ${searchMatches.size}")
    }

    private fun replaceCurrentMatch() {
        if (currentMatchIndex < 0 || searchMatches.isEmpty()) { showToast("Нет совпадений"); return }
        val replaceText = binding.etReplace.text.toString()
        val range = searchMatches[currentMatchIndex]
        val editable = binding.etScript.text ?: return
        editable.replace(range.first, range.last + 1, replaceText)
        performSearch(); showToast("Заменено")
    }

    private fun showReplaceAllDialog() {
        val query = binding.etSearch.text.toString()
        val replaceText = binding.etReplace.text.toString()
        if (query.isEmpty()) { showToast("Поле поиска пустое"); return }
        val darkContext = ContextThemeWrapper(this, R.style.DarkDialogTheme)
        AlertDialog.Builder(darkContext)
            .setTitle("Заменить всё?")
            .setMessage("Заменить все вхождения \"$query\" на \"$replaceText\"?")
            .setPositiveButton("ДА") { _, _ ->
                try {
                    val text = binding.etScript.text?.toString() ?: ""
                    val newText = Regex(Regex.escape(query), RegexOption.IGNORE_CASE).replace(text, replaceText)
                    binding.etScript.setText(newText); binding.etScript.setSelection(0)
                    searchMatches = emptyList(); currentMatchIndex = -1
                    showToast("Все совпадения заменены")
                } catch (e: Exception) { showToast("Ошибка: ${e.message}") }
            }
            .setNegativeButton("ОТМЕНА", null).show()
    }

    private fun setupQuickScroll() {
        binding.root.setOnTouchListener { v, ev ->
            try {
                val edgeWidthPx = (24 * resources.displayMetrics.density).toInt()
                if (ev.x <= edgeWidthPx && (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE)) {
                    val layout = binding.etScript.layout ?: return@setOnTouchListener true
                    val ratio = (ev.y / v.height).coerceIn(0f, 1f)
                    val targetLine = ((layout.lineCount - 1) * ratio).toInt().coerceIn(0, max(0, layout.lineCount - 1))
                    val offset = layout.getLineStart(targetLine)
                    binding.etScript.requestFocus(); binding.etScript.setSelection(offset)
                    binding.etScript.scrollTo(0, layout.getLineTop(targetLine))
                    return@setOnTouchListener true
                }
            } catch (_: Exception) { }
            false
        }
    }

    private class RowanHighlighter(
        private val editText: EditText,
        private val lifecycle: androidx.lifecycle.Lifecycle
    ) : TextWatcher {
        private val colorKeyword = Color.parseColor("#C792EA")
        private val colorString = Color.parseColor("#C3E88D")
        private val colorComment = Color.parseColor("#546E7A")
        private val colorNumber = Color.parseColor("#F78C6C")
        private val colorFunction = Color.parseColor("#82AAFF")
        private val colorOperator = Color.parseColor("#89DDFF")

        private val stringPattern = Pattern.compile("(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*')")
        private val commentPattern = Pattern.compile("(//.*|/\\*[\\s\\S]*?\\*/|#.*)")
        
        // СИНТАКСИС ROWAN
        private val keywordPattern = Pattern.compile("\\b(if|else|elif|for|while|do|return|break|continue|try|catch|finally|throw|val|var|const|fn|function|struct|view|match|case|mem|in|is|not|and|or|xor|shl|shr|true|false|null|void|exit|assert|to)\\b")
        
        private val functionPattern = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(")
        private val numberPattern = Pattern.compile("\\b(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?|0b[01]+|0x[0-9A-Fa-f]+)\\b")
        private val operatorPattern = Pattern.compile("(==|!=|<=|>=|&&|\\|\\||\\+\\+|--|<<|>>|\\+=|-=|\\*=|/=|%=|\\.)")

        private var debounceJob: Job? = null
        private val debounceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        @Volatile private var isActive = true

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable?) {
            if (!isActive || s == null) return
            debounceJob?.cancel()
            debounceJob = debounceScope.launch {
                delay(300L)
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
            } catch (e: Exception) { android.util.Log.w("RowanHighlighter", "Error", e) }
        }
        
        private fun applyMinimalHighlighting(editable: Editable, text: String) {
            removeOldSpans(editable)
            applyPatternSafe(editable, text, stringPattern, colorString)
            applyPatternSafe(editable, text, commentPattern, colorComment)
        }
        
        private fun removeOldSpans(editable: Editable) {
            val oldSpans = editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
            for (span in oldSpans) { try { editable.removeSpan(span) } catch (e: Exception) {} }
        }

        private fun applyPatternSafe(editable: Editable, text: String, pattern: Pattern, color: Int) {
            try {
                val matcher = pattern.matcher(text)
                while (matcher.find()) {
                    if (!isActive) return
                    val start = matcher.start(); val end = matcher.end()
                    if (start < 0 || end > text.length || start >= end) continue
                    if (!isInsideStringOrComment(text, start, end)) {
                        try { editable.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {}
        }
        
        private fun isInsideStringOrComment(text: String, start: Int, end: Int): Boolean {
            try {
                var inDoubleQuote = false; var inSingleQuote = false; var blockCommentDepth = 0; var i = 0
                while (i < start) {
                    if (text[i] == '\\') { i += 2; continue }
                    val char = text[i]
                    when (char) {
                        '"' -> if (!inSingleQuote && blockCommentDepth == 0) inDoubleQuote = !inDoubleQuote
                        '\'' -> if (!inDoubleQuote && blockCommentDepth == 0) inSingleQuote = !inSingleQuote
                    }
                    if (blockCommentDepth == 0) {
                        if (i < text.length - 1 && text[i] == '/' && text[i + 1] == '*') { blockCommentDepth++; i += 2; continue }
                    } else {
                        if (i < text.length - 1 && text[i] == '*' && text[i + 1] == '/') { blockCommentDepth--; i += 2; continue }
                    }
                    i++
                }
                if (inDoubleQuote || inSingleQuote || blockCommentDepth > 0) return true
                val lineStart = text.lastIndexOf('\n', start - 1) + 1
                if (text.substring(lineStart, start).contains("//") || text.substring(lineStart, start).contains("#")) return true
                return false
            } catch (e: Exception) { return false }
        }
        
        fun cleanup() { isActive = false; debounceJob?.cancel(); debounceScope.cancel() }
    }
}
